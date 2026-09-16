package com.star_pick.starpick.domain.ingestion.infrastructure.youtube;

import com.star_pick.starpick.domain.ingestion.service.YouTubeMetadataClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * YouTube Data API v3 {@code videos.list} 로 영상 설명란을 읽는다.
 *
 * <p><b>키는 헤더로 보낸다.</b> 쿼리로 보내면 예외 메시지·프록시·액세스 로그에 키가 실린다.
 * {@code x-goog-api-key} 가 이 API 에서도 동작하는 것을 실측으로 확인했다(2026-09-16).
 */
class YouTubeDataApiClient implements YouTubeMetadataClient {

    private final RestClient.Builder restClientBuilder;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;

    YouTubeDataApiClient(RestClient.Builder restClientBuilder, HttpClient httpClient,
                         String baseUrl, String apiKey) {
        this.restClientBuilder = restClientBuilder;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String description(String videoId, Duration timeout) {
        JsonNode body = clientFor(timeout).get()
                .uri(baseUrl + "/youtube/v3/videos?part=snippet&id={id}", videoId)
                .header("x-goog-api-key", apiKey)
                .retrieve()
                .body(JsonNode.class);
        if (body == null) {
            return null;
        }
        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        String description = items.get(0).path("snippet").path("description").asString();
        return StringUtils.hasText(description) ? description.strip() : null;
    }

    private RestClient clientFor(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return restClientBuilder.clone().requestFactory(factory).build();
    }
}
