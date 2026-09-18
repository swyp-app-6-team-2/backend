package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.ReelVideoResolver;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 토큰이 실제로 채워졌을 때만 보조 수집기를 만든다. 비어 있으면 Worker 가 캡션 분석으로 내려간다. */
@Configuration
@ConditionalOnProperty(prefix = "ingestion.external", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${ingestion.apify.token:}'.length() > 0")
public class ApifyConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    ReelVideoResolver reelVideoResolver(RestClient.Builder restClientBuilder, JsonMapper jsonMapper,
                                        IngestionProperties properties) {
        IngestionProperties.Apify apify = properties.apify();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        return new ApifyReelClient(restClientBuilder, httpClient, jsonMapper,
                apify.baseUrl(), apify.actorId(), apify.token());
    }
}
