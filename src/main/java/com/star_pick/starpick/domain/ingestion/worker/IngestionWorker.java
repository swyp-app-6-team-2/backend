package com.star_pick.starpick.domain.ingestion.worker;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobExecutionService;
import com.star_pick.starpick.domain.ingestion.service.PreemptedJob;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IngestionWorker {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final IngestionProperties properties;
    private final IngestionJobExecutionService executionService;
    private final IngestionJobProcessor processor;
    private final ThreadPoolTaskExecutor ingestionExecutor;

    public IngestionWorker(IngestionProperties properties,
                           IngestionJobExecutionService executionService,
                           IngestionJobProcessor processor,
                           ThreadPoolTaskExecutor ingestionExecutor) {
        this.properties = properties;
        this.executionService = executionService;
        this.processor = processor;
        this.ingestionExecutor = ingestionExecutor;
    }

    @Scheduled(fixedDelayString = "${ingestion.worker.poll-interval}")
    public void poll() {
        int free = properties.worker().concurrency() - inFlight.get();
        if (free <= 0) {
            return;
        }

        List<PreemptedJob> preempted;
        try {
            preempted = executionService.preempt(free);
        } catch (RuntimeException e) {
            log.error("Ingestion Job 선점에 실패했습니다.", e);
            return;
        }

        for (PreemptedJob job : preempted) {
            inFlight.incrementAndGet();
            try {
                ingestionExecutor.execute(() -> {
                    try {
                        processor.process(job);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                inFlight.decrementAndGet();
                // 선점이 이미 커밋돼 PROCESSING 이므로 되돌리지 않으면 stale 기준 3분을 기다리고,
                // 그 사이 시도 예산 한 번이 소모된다. 슬롯이 빈 것으로 보였는데 거부되는 경우가
                // 실제로 있다 — inFlight 는 작업 코드 안에서 줄어들고, 그 워커 스레드가 큐로
                // 되돌아가기 전에 다음 poll 이 끼어들 수 있다.
                boolean released = executionService.releaseToQueued(job.id(), job.attempt());
                log.warn("실행 슬롯이 없어 Job 을 대기로 되돌렸습니다. ingestionJobId={}, released={}",
                        job.id(), released);
            }
        }
    }
}
