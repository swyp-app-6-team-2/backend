package com.star_pick.starpick.domain.ingestion.worker;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobPurger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionMaintenance {

    private static final int PURGE_BATCH_SIZE = 100;

    private final IngestionJobRepository repository;
    private final IngestionJobPurger purger;
    private final IngestionProperties properties;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void requeueOrFailStale() {
        Instant threshold = Instant.now().minus(properties.job().staleThreshold());
        int requeued = repository.requeueStale(threshold);
        int failed = repository.failStale(threshold);
        if (requeued + failed > 0) {
            log.warn("stale Job 을 복구했습니다. requeued={}, failed={}", requeued, failed);
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void failStuckQueued() {
        int failed = repository.failStuckQueued(
                Instant.now().minus(properties.job().queueWaitLimit()));
        if (failed > 0) {
            log.warn("대기 상한을 넘긴 Ingestion Job 을 실패시켰습니다. failed={}", failed);
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void expireResults() {
        int expired = repository.expireResults(Instant.now());
        if (expired > 0) {
            log.info("Ingestion 결과를 만료시켰습니다. expired={}", expired);
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void purgeOldJobs() {
        Instant threshold = Instant.now().minus(properties.job().retention());
        List<Long> ids = repository.findPurgeTargetIds(
                threshold, PageRequest.of(0, PURGE_BATCH_SIZE));
        int purged = 0;
        for (Long id : ids) {
            // 건별로 잡는다. 한 건이 던지면 남은 Job 이 다음 주기(1시간)까지 그대로 남는다.
            // 조회와 삭제 사이에 다른 인스턴스가 같은 행을 지우면 delete 가 0행이 되어
            // OptimisticLockingFailureException 이 나는데, 그건 이미 목적을 달성한 상태다.
            try {
                purger.purgeOne(id);
                purged++;
            } catch (RuntimeException e) {
                log.warn("Ingestion Job 정리에 실패했습니다. 다음 주기에 다시 시도합니다. ingestionJobId={}",
                        id, e);
            }
        }
        if (purged > 0) {
            log.info("오래된 Ingestion Job 을 정리했습니다. purged={}, targets={}", purged, ids.size());
        }
    }
}
