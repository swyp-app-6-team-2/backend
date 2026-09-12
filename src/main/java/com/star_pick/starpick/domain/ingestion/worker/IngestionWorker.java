package com.star_pick.starpick.domain.ingestion.worker;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobExecutionService;
import com.star_pick.starpick.domain.ingestion.service.PreemptedJob;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IngestionWorker {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final IngestionProperties properties;
    private final IngestionJobExecutionService executionService;
    private final IngestionJobProcessor processor;

    /**
     * 분석 실행 스레드 풀. <b>컨테이너 빈으로 내놓지 않고 이 클래스가 직접 소유한다.</b>
     *
     * <p>{@code Executor} 타입 빈을 등록하면 Boot 의 {@code applicationTaskExecutor} 자동설정
     * 조건({@code @ConditionalOnMissingBean(Executor.class)})이 꺾여, MVC async 와 {@code @Async}
     * 가 이 풀을 함께 쓰게 된다. 그걸 되돌리려면 {@code spring.task.execution.mode: force} 같은
     * 전역 설정이 필요한데, 한 도메인의 사정으로 프레임워크 전역 계약을 건드리는 셈이다.
     * 소비자가 여기 하나뿐이므로 애초에 내놓지 않는 편이 간단하다.
     *
     * <p>큐를 두지 않는다({@link SynchronousQueue}). 동시 처리 수는 {@link #inFlight} 가 관리하고,
     * 이 풀은 그 계산이 틀렸을 때 조용히 쌓이는 대신 거부로 드러나게 하는 마지막 방어선이다.
     */
    private final ExecutorService ingestionExecutor;

    public IngestionWorker(IngestionProperties properties,
                           IngestionJobExecutionService executionService,
                           IngestionJobProcessor processor) {
        this.properties = properties;
        this.executionService = executionService;
        this.processor = processor;
        int concurrency = properties.worker().concurrency();
        this.ingestionExecutor = new ThreadPoolExecutor(
                concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().name("ingestion-", 1).factory());
    }

    /**
     * 진행 중 작업을 기다리지 않는다. 끊긴 Job 은 stale 복구가 3분 뒤 다시 처리하며,
     * 재배포 시 최대 약 3분 지연은 감수하기로 한 값이다.
     */
    @PreDestroy
    void shutdown() {
        ingestionExecutor.shutdownNow();
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
