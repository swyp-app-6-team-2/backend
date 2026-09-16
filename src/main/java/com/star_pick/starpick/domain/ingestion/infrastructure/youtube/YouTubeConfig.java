package com.star_pick.starpick.domain.ingestion.infrastructure.youtube;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.YouTubeMetadataClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** {@code GeminiConfig} 와 같은 구조다. 테스트는 {@code FakeYouTubeMetadataClient} 를 끼운다. */
@Configuration
@ConditionalOnProperty(prefix = "ingestion.external", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class YouTubeConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    YouTubeMetadataClient youTubeMetadataClient(RestClient.Builder restClientBuilder,
                                                IngestionProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        return new YouTubeDataApiClient(restClientBuilder, httpClient,
                "https://www.googleapis.com", properties.youtube().apiKey());
    }
}
