package com.star_pick.starpick.domain.ingestion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Worker 를 맡는 프로세스의 필수 설정을 확인하고 기동 로그를 남긴다. 맡을지 말지는
 * {@link IngestionWorkerCondition} 이 정하고, 주기 실행은 같은 조건으로 등록되는
 * {@code IngestionSchedule} 이 한다.
 *
 * <p><b>YouTube 키가 없으면 여기서 기동을 막는다.</b> 이 Bean 은 {@code @Configuration} 이라
 * 생성자 예외가 Worker 만이 아니라 애플리케이션 전체를 못 뜨게 한다 — 의도한 것이다.
 */
@Slf4j
@Configuration
@Conditional(IngestionWorkerCondition.class)
public class IngestionWorkerConfig {

    public IngestionWorkerConfig(IngestionProperties properties) {
        // Worker 를 맡을 때만 필수다. 없으면 YouTube 분석이 설명란 없이 돌아 품질만 조용히 떨어진다 —
        // 기동도 헬스체크도 통과하고 오류 로그도 없어서 알아챌 방법이 없으므로 기동을 막는다.
        // GEMINI_API_KEY 를 스위치 대신 쓰는 것과 같은 이유다(IngestionWorkerCondition 참고).
        if (!StringUtils.hasText(properties.youtube().apiKey())) {
            throw new IllegalStateException(
                    "Ingestion Worker 를 맡으려면 ingestion.youtube.api-key 가 필요합니다.");
        }
        log.info("Ingestion Worker 를 시작합니다. concurrency={}, pollInterval={}",
                properties.worker().concurrency(), properties.worker().pollInterval());
    }
}
