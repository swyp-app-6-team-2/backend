package com.star_pick.starpick.domain.ingestion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 를 맡는 프로세스에서 기동 로그를 남긴다. 맡을지 말지는 {@link IngestionWorkerCondition} 이 정하고,
 * 주기 실행은 같은 조건으로 등록되는 {@code IngestionSchedule} 이 한다.
 */
@Slf4j
@Configuration
@Conditional(IngestionWorkerCondition.class)
public class IngestionWorkerConfig {

    public IngestionWorkerConfig(IngestionProperties properties) {
        log.info("Ingestion Worker 를 시작합니다. concurrency={}, pollInterval={}",
                properties.worker().concurrency(), properties.worker().pollInterval());
    }
}
