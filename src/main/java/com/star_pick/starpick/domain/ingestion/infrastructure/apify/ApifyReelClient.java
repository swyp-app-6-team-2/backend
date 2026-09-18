package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

import com.star_pick.starpick.domain.ingestion.service.InstagramMediaUrls;
import com.star_pick.starpick.domain.ingestion.service.ReelVideo;
import com.star_pick.starpick.domain.ingestion.service.ReelVideoResolver;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Apify Actor 로 Reel 원본 주소를 받는다. 공개 embed 가 영상을 주지 않는 음원 Reel 이 대상이다.
 *
 * <p>동기 실행 endpoint 를 쓴다(2026-09-18 실측 7~14초, 건당 $0.0036). 실패는 예외 대신 빈 값이라
 * 호출자가 캡션 분석으로 내려갈 수 있다. 토큰·응답 본문·링크는 로그에 남기지 않는다.
 */
public class ApifyReelClient implements ReelVideoResolver {

    private final RestClient.Builder restClientBuilder;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String baseUrl;
    private final String actorId;
    private final String token;

    public ApifyReelClient(RestClient.Builder restClientBuilder, HttpClient httpClient, JsonMapper jsonMapper,
                           String baseUrl, String actorId, String token) {
        this.restClientBuilder = restClientBuilder;
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
        this.baseUrl = baseUrl;
        this.actorId = actorId;
        this.token = token;
    }

    @Override
    public Optional<ReelVideo> resolve(String shortcode, String url, Duration timeout) {
        try {
            String body = restClientBuilder.clone()
                    .requestFactory(requestFactory(timeout))
                    .build()
                    .post()
                    .uri(baseUrl + "/v2/acts/" + actorId + "/run-sync-get-dataset-items?timeout="
                            + timeout.toSeconds())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(new ApifyInput(List.of(url), 1)))
                    .retrieve()
                    .body(String.class);
            return parse(body, shortcode);
        } catch (RuntimeException e) {
            throw new ApifyUnavailableException(reason(e));
        }
    }

    private Optional<ReelVideo> parse(String body, String shortcode) {
        JsonNode items = jsonMapper.readTree(body == null ? "[]" : body);
        if (!items.isArray() || items.isEmpty()) {
            return Optional.empty();
        }
        JsonNode item = items.get(0);
        if (!isRequested(item, shortcode)) {
            return Optional.empty();
        }
        String videoUrl = allowedOrNull(item.path("videoUrl").asString(null));
        if (videoUrl == null) {
            return Optional.empty();
        }
        return Optional.of(new ReelVideo(videoUrl, blankToNull(item.path("caption").asString(null)),
                allowedOrNull(item.path("displayUrl").asString(null))));
    }

    /**
     * 받은 항목이 우리가 요청한 Reel 인지 확인한다. actor 의 {@code username} 은 원래 프로필용 필드라
     * Apify 가 링크를 게시자 프로필로 읽으면 그 계정의 <b>최신</b> Reel 이 온다. 확인할 수 없는 항목도 버린다 —
     * 다른 영상의 레시피를 성공으로 돌려주는 것보다 캡션 분석으로 내려가는 편이 낫다.
     */
    private static boolean isRequested(JsonNode item, String shortcode) {
        String code = item.path("shortCode").asString(null);
        if (code != null) {
            return code.equals(shortcode);
        }
        String url = item.path("url").asString(null);
        return url != null && url.contains("/" + shortcode + "/");
    }

    /** 빈 캡션은 없는 것으로 본다. 호출자가 embed 캡션을 이 값으로 덮지 않게 한다. */
    private static String blankToNull(String caption) {
        return caption == null || caption.isBlank() ? null : caption;
    }

    /** 외부가 준 주소도 우리 허용 호스트 규칙을 통과해야 한다. embed 가 준 주소와 같은 규칙이다. */
    private static String allowedOrNull(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            return null;
        }
        try {
            return InstagramMediaUrls.isAllowed(new URI(mediaUrl)) ? mediaUrl : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 실패 원인으로 남길 짧은 문자열. <b>상태 코드가 있으면 담는다</b> — 월 한도 소진(402·429)과 timeout·
     * 응답 해석 실패를 구분해야 "보조 수집이 빠진 채 캡션 분석으로만 도는" 상태를 알아챌 수 있다.
     *
     * <p>{@code IngestionJobProcessor#cause} 와 같은 방식이고 같은 이유다. URL·응답 본문·토큰은 담지
     * 않는다 — 예외 메시지에 응답 본문이 들어 있을 수 있어 {@code getMessage()} 를 그대로 쓰지 않는다.
     */
    private static String reason(RuntimeException e) {
        String name = e.getClass().getSimpleName();
        if (e instanceof RestClientResponseException response) {
            return name + " status=" + response.getStatusCode().value();
        }
        if (e instanceof UnknownContentTypeException unknown) {
            return name + " status=" + unknown.getStatusCode().value();
        }
        return name;
    }

    /** 공유 {@link HttpClient} 를 쓴다. 호출마다 새로 만들면 셀렉터 스레드가 GC 때까지 쌓인다. */
    private JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return factory;
    }

    private record ApifyInput(List<String> username, int resultsLimit) {
    }
}
