package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

import com.star_pick.starpick.domain.ingestion.service.InstagramMediaUrls;
import com.star_pick.starpick.domain.ingestion.service.ReelVideo;
import com.star_pick.starpick.domain.ingestion.service.ReelVideoResolver;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
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
    private final JsonMapper jsonMapper;
    private final String baseUrl;
    private final String actorId;
    private final String token;

    public ApifyReelClient(RestClient.Builder restClientBuilder, JsonMapper jsonMapper,
                           String baseUrl, String actorId, String token) {
        this.restClientBuilder = restClientBuilder;
        this.jsonMapper = jsonMapper;
        this.baseUrl = baseUrl;
        this.actorId = actorId;
        this.token = token;
    }

    @Override
    public Optional<ReelVideo> resolve(String url, Duration timeout) {
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
            return parse(body);
        } catch (RuntimeException e) {
            throw new ApifyUnavailableException(e.getClass().getSimpleName());
        }
    }

    private Optional<ReelVideo> parse(String body) {
        JsonNode items = jsonMapper.readTree(body == null ? "[]" : body);
        if (!items.isArray() || items.isEmpty()) {
            return Optional.empty();
        }
        JsonNode item = items.get(0);
        String videoUrl = allowedOrNull(item.path("videoUrl").asString(null));
        if (videoUrl == null) {
            return Optional.empty();
        }
        return Optional.of(new ReelVideo(videoUrl, item.path("caption").asString(null),
                allowedOrNull(item.path("displayUrl").asString(null))));
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

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(timeout);
        return factory;
    }

    private record ApifyInput(List<String> username, int resultsLimit) {
    }
}
