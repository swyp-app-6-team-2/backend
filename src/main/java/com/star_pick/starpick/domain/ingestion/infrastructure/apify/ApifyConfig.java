package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.ReelVideoResolver;
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

    @Bean
    ReelVideoResolver reelVideoResolver(RestClient.Builder restClientBuilder, JsonMapper jsonMapper,
                                        IngestionProperties properties) {
        IngestionProperties.Apify apify = properties.apify();
        return new ApifyReelClient(restClientBuilder, jsonMapper, apify.baseUrl(), apify.actorId(), apify.token());
    }
}
