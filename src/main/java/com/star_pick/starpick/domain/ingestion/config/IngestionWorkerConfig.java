package com.star_pick.starpick.domain.ingestion.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "ingestion.worker", name = "enabled", havingValue = "true")
public class IngestionWorkerConfig {

    public IngestionWorkerConfig(IngestionProperties properties) {
        if (properties.external().enabled() && !StringUtils.hasText(properties.gemini().apiKey())) {
            throw new IllegalStateException(
                    "ingestion.gemini.api-key 가 없으면 Worker 를 켤 수 없습니다. "
                            + "키를 주거나 ingestion.worker.enabled 를 끄십시오.");
        }
    }
}
