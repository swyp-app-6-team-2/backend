package com.star_pick.starpick.domain.ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobExecutionService;
import com.star_pick.starpick.domain.ingestion.service.PreemptedJob;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class IngestionJobPreemptionTest {

    @Autowired
    private IngestionJobRepository repository;
    @Autowired
    private IngestionJobExecutionService executionService;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(1L);
    }

    @Test
    @DisplayName("QUEUED Job을 ID 순서로 제한 수만큼 선점하고 커밋한다")
    void preemptsQueuedJobsInOrder() {
        Long first = save("a");
        Long second = save("b");
        save("c");

        List<PreemptedJob> selected = executionService.preempt(2);

        assertThat(selected).containsExactly(new PreemptedJob(first, 1), new PreemptedJob(second, 1));
        assertThat(repository.findById(first).orElseThrow().getStatus()).isEqualTo(IngestionJobStatus.PROCESSING);
        assertThat(repository.findById(first).orElseThrow().getStartedAt()).isNotNull();
        assertThat(executionService.preempt(2)).hasSize(1);
    }

    @Test
    @DisplayName("QUEUED Job이 없으면 선점 결과가 비어 있다")
    void returnsEmptyWhenNoQueuedJob() {
        assertThat(executionService.preempt(2)).isEmpty();
    }

    @Test
    @DisplayName("QUEUED가 아닌 종료·처리 상태와 이미 선점한 Job은 다시 선점하지 않는다")
    void skipsNonQueuedAndAlreadyPreemptedJobs() {
        Long processing = save("processing");
        Long ready = save("ready");
        Long failed = save("failed");
        Long expired = save("expired");
        jdbcTemplate.update("update ingestion_job set status = 'PROCESSING', attempt = 1 where id = ?", processing);
        jdbcTemplate.update("update ingestion_job set status = 'RESULT_READY' where id = ?", ready);
        jdbcTemplate.update("update ingestion_job set status = 'FAILED', failure_code = 'PROCESSING_FAILED' where id = ?", failed);
        jdbcTemplate.update("update ingestion_job set status = 'EXPIRED' where id = ?", expired);

        assertThat(executionService.preempt(10)).isEmpty();

        Long queued = save("once");
        assertThat(executionService.preempt(1)).containsExactly(new PreemptedJob(queued, 1));
        assertThat(executionService.preempt(1)).isEmpty();
    }

    @Test
    @Timeout(30)
    @DisplayName("다른 트랜잭션이 잠근 Job을 기다리지 않고 다음 Job을 선점한다")
    void skipsLockedRow() throws Exception {
        Long first = save("locked");
        Long second = save("available");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("select id from ingestion_job where id = ? for update", Long.class, first);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            CompletableFuture<List<PreemptedJob>> contender = CompletableFuture.supplyAsync(
                    () -> executionService.preempt(1));
            assertThat(contender.get(2, TimeUnit.SECONDS))
                    .containsExactly(new PreemptedJob(second, 1));
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("실행에 넘기지 못한 Job을 대기로 되돌린다. attempt는 되돌리지 않는다")
    void releasesPreemptedJobBackToQueued() {
        Long id = save("rejected");
        PreemptedJob preempted = executionService.preempt(1).getFirst();

        assertThat(executionService.releaseToQueued(preempted.id(), preempted.attempt())).isTrue();

        IngestionJob job = repository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.QUEUED);
        // attempt 를 되돌리면 이미 무효가 된 시도의 늦은 결과가 다시 유효해진다.
        assertThat(job.getAttempt()).isEqualTo(1);
        assertThat(executionService.preempt(1)).containsExactly(new PreemptedJob(id, 2));
    }

    @Test
    @DisplayName("시도가 이미 무효면 되돌리지 않는다")
    void doesNotReleaseWhenAttemptIsStale() {
        Long id = save("stale");
        PreemptedJob preempted = executionService.preempt(1).getFirst();

        assertThat(executionService.releaseToQueued(preempted.id(), preempted.attempt() + 1)).isFalse();
        assertThat(repository.findById(id).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.PROCESSING);
    }

    private Long save(String name) {
        return repository.save(IngestionJob.queueImage(1L,
                List.of("ingestion-inputs/1/" + name + ".jpg"))).getId();
    }
}
