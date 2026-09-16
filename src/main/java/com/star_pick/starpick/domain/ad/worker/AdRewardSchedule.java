package com.star_pick.starpick.domain.ad.worker;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 광고 보상 주기 작업의 진입점. {@code IngestionSchedule} 과 같은 구조다.
 *
 * <p>{@code ad-reward.external.enabled=false} 면 등록되지 않는다 — 테스트가 세션 시각 컬럼을
 * 과거로 조작해 만료를 검증하는데, 실제 스케줄러가 같은 컨테이너에서 동시에 돌면 그 픽스처를
 * 먼저 쓸어가 flaky 를 만든다({@code IngestionWorkerCondition} 과 같은 이유). 실제 만료 로직은
 * {@link AdRewardMaintenance} 가 조건 없이 갖고 있어 테스트가 직접 부를 수 있다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ad-reward.external", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdRewardSchedule {

    private final AdRewardMaintenance maintenance;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void expireOverdueSessions() {
        maintenance.expireOverdueSessions();
    }
}
