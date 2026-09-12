package com.star_pick.starpick.domain.ingestion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker 의 주기 실행을 켠다. 맡을지 말지는 {@link IngestionWorkerCondition} 이 정한다.
 *
 * <p>{@code IngestionWorker} 와 {@code IngestionMaintenance} 는 조건 없이 등록된다. 여기서 켜는
 * 것은 {@code @Scheduled} 를 읽는 기능뿐이라, 맡지 않은 프로세스에서도 두 빈은 존재하되 아무도
 * 부르지 않는다. 테스트가 그 빈을 직접 호출해 검증할 수 있는 이유이기도 하다.
 */
@Slf4j
@Configuration
@EnableScheduling
@Conditional(IngestionWorkerCondition.class)
public class IngestionWorkerConfig {

    public IngestionWorkerConfig(IngestionProperties properties) {
        log.info("Ingestion Worker 를 시작합니다. concurrency={}, pollInterval={}",
                properties.worker().concurrency(), properties.worker().pollInterval());
    }
}
