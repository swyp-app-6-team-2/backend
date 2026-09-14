package com.star_pick.starpick.domain.ingestion.worker;

import com.star_pick.starpick.domain.ingestion.config.IngestionWorkerCondition;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ingestion 주기 작업의 진입점. Worker 를 맡는 프로세스에만 등록된다.
 *
 * <p>실제 일은 조건 없이 등록되는 {@link IngestionWorker}·{@link IngestionMaintenance} 가 한다.
 * 테스트가 그 Bean 을 직접 호출해 검증할 수 있게 하기 위해서다.
 */
@Component
@RequiredArgsConstructor
@Conditional(IngestionWorkerCondition.class)
public class IngestionSchedule {

    private final IngestionWorker worker;
    private final IngestionMaintenance maintenance;

    @Scheduled(fixedDelayString = "${ingestion.worker.poll-interval}")
    public void poll() {
        worker.poll();
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void requeueOrFailStale() {
        maintenance.requeueOrFailStale();
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void failStuckQueued() {
        maintenance.failStuckQueued();
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void expireResults() {
        maintenance.expireResults();
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void purgeOldJobs() {
        maintenance.purgeOldJobs();
    }
}
