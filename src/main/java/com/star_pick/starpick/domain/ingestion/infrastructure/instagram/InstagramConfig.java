package com.star_pick.starpick.domain.ingestion.infrastructure.instagram;

import com.star_pick.starpick.domain.ingestion.service.InstagramClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** {@code GeminiConfig} 와 같은 구조다. 테스트는 {@code FakeInstagramClient} 를 끼운다. */
@Configuration
@ConditionalOnProperty(prefix = "ingestion.external", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InstagramConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    InstagramClient instagramClient(RestClient.Builder restClientBuilder, JsonMapper jsonMapper) {
        // 리다이렉트를 따라가지 않는다. 로그인 페이지 이동을 수집 실패로 드러내고 허용 호스트 밖으로 끌려가지 않는다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new InstagramEmbedClient(restClientBuilder, httpClient, jsonMapper,
                "https://www.instagram.com", InstagramEmbedClient::isAllowedMediaUrl);
    }
}
