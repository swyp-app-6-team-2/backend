package com.star_pick.starpick.domain.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.repository.UploadObjectRepository;
import com.star_pick.starpick.domain.upload.service.AttachOutcome;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IngestionMaintenanceTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private IngestionMaintenance maintenance;
    @Autowired
    private IngestionJobRepository jobRepository;
    @Autowired
    private UploadObjectRepository uploadObjectRepository;
    @Autowired
    private UploadService uploadService;
    @Autowired
    private FakeObjectStorage objectStorage;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(USER_ID);
    }

    @Test
    @DisplayName("stale 첫 시도는 재대기시키고 두 번째 시도는 실패시키며 아직 유효한 작업은 유지한다")
    void recoversStaleProcessingJobs() {
        Long firstAttempt = saveJob("first.jpg");
        Long secondAttempt = saveJob("second.jpg");
        Long fresh = saveJob("fresh.jpg");
        Instant stale = Instant.now().minus(4, ChronoUnit.MINUTES);
        Instant notStale = Instant.now().minus(2, ChronoUnit.MINUTES);
        markProcessing(firstAttempt, 1, stale);
        markProcessing(secondAttempt, 2, stale);
        markProcessing(fresh, 1, notStale);

        maintenance.requeueOrFailStale();

        assertState(firstAttempt, IngestionJobStatus.QUEUED, null);
        assertState(secondAttempt, IngestionJobStatus.FAILED, IngestionFailureCode.PROCESSING_FAILED);
        assertState(fresh, IngestionJobStatus.PROCESSING, null);
    }

    @Test
    @DisplayName("대기 상한을 넘긴 QUEUED 작업만 실패시킨다")
    void failsOnlyStuckQueuedJobs() {
        Long stuck = saveJob("stuck.jpg");
        Long fresh = saveJob("fresh.jpg");
        setCreatedAt(stuck, Instant.now().minus(11, ChronoUnit.MINUTES));
        setCreatedAt(fresh, Instant.now().minus(9, ChronoUnit.MINUTES));

        maintenance.failStuckQueued();

        assertState(stuck, IngestionJobStatus.FAILED, IngestionFailureCode.PROCESSING_FAILED);
        assertState(fresh, IngestionJobStatus.QUEUED, null);
    }

    @Test
    @DisplayName("기한이 지난 미소비 결과만 만료시키고 결과 JSON을 지운다")
    void expiresOnlyUnconsumedPastResults() {
        Long expired = saveJob("expired.jpg");
        Long consumed = saveJob("consumed.jpg");
        Long future = saveJob("future.jpg");
        markReady(expired, Instant.now().minus(1, ChronoUnit.MINUTES), null);
        markReady(consumed, Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now());
        markReady(future, Instant.now().plus(1, ChronoUnit.HOURS), null);

        maintenance.expireResults();

        IngestionJob expiredJob = jobRepository.findById(expired).orElseThrow();
        assertThat(expiredJob.getStatus()).isEqualTo(IngestionJobStatus.EXPIRED);
        assertThat(expiredJob.getResult()).isNull();
        assertThat(jobRepository.findById(consumed).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.RESULT_READY);
        assertThat(jobRepository.findById(consumed).orElseThrow().getResult()).isNotNull();
        assertThat(jobRepository.findById(future).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.RESULT_READY);
    }

    @Test
    @DisplayName("보존 기간이 지난 미소비 종료 작업만 사진과 함께 삭제한다")
    void purgesOnlyOldUnconsumedTerminalJobs() {
        PurgeFixture oldFailed = attachedJob("old-failed.jpg");
        PurgeFixture oldExpired = attachedJob("old-expired.jpg");
        PurgeFixture recentFailed = attachedJob("recent-failed.jpg");
        PurgeFixture consumedFailed = attachedJob("consumed-failed.jpg");
        PurgeFixture ready = attachedJob("ready.jpg");
        Instant old = Instant.now().minus(8, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(6, ChronoUnit.DAYS);
        markFailed(oldFailed.id(), old, null);
        markExpired(oldExpired.id(), old);
        markFailed(recentFailed.id(), recent, null);
        markFailed(consumedFailed.id(), old, Instant.now());
        markReady(ready.id(), old, null);

        maintenance.purgeOldJobs();

        assertPurged(oldFailed);
        assertPurged(oldExpired);
        assertThat(jobRepository.existsById(recentFailed.id())).isTrue();
        assertThat(jobRepository.existsById(consumedFailed.id())).isTrue();
        assertThat(jobRepository.existsById(ready.id())).isTrue();
    }

    @Test
    @DisplayName("startedAt이 없는 FAILED 작업은 createdAt을 기준으로 정리한다")
    void purgesFailedJobWithoutStartedAtByCreatedAt() {
        PurgeFixture old = attachedJob("old-never-started.jpg");
        PurgeFixture recent = attachedJob("recent-never-started.jpg");
        markFailed(old.id(), null, null);
        markFailed(recent.id(), null, null);
        setCreatedAt(old.id(), Instant.now().minus(8, ChronoUnit.DAYS));
        setCreatedAt(recent.id(), Instant.now().minus(6, ChronoUnit.DAYS));

        maintenance.purgeOldJobs();

        assertPurged(old);
        assertThat(jobRepository.existsById(recent.id())).isTrue();
        assertThat(objectStorage.contains(recent.key())).isTrue();
    }

    private Long saveJob(String suffix) {
        return jobRepository.save(IngestionJob.queueImage(
                USER_ID, List.of("ingestion-inputs/1/" + suffix))).getId();
    }

    private PurgeFixture attachedJob(String suffix) {
        String key = fixtures.uploadedKey(USER_ID, UploadPurpose.INGESTION_INPUT);
        assertThat(uploadService.attach(USER_ID, key, UploadPurpose.INGESTION_INPUT))
                .isEqualTo(AttachOutcome.ATTACHED);
        Long id = jobRepository.save(IngestionJob.queueImage(USER_ID, List.of(key))).getId();
        return new PurgeFixture(id, key);
    }

    private void markProcessing(Long id, int attempt, Instant startedAt) {
        jdbcTemplate.update("""
                update ingestion_job
                   set status = 'PROCESSING', attempt = ?, started_at = ?
                 where id = ?
                """, attempt, sqlTime(startedAt), id);
    }

    private void setCreatedAt(Long id, Instant createdAt) {
        jdbcTemplate.update(
                "update ingestion_job set created_at = ? where id = ?", sqlTime(createdAt), id);
    }

    private void markReady(Long id, Instant expiresAt, Instant consumedAt) {
        jdbcTemplate.update("""
                update ingestion_job
                   set status = 'RESULT_READY',
                       result = cast(? as jsonb),
                       expires_at = ?,
                       consumed_at = ?
                 where id = ?
                """, "{\"title\":\"감자전\",\"ingredients\":[],\"steps\":[]}",
                sqlTime(expiresAt), sqlTime(consumedAt), id);
    }

    private void markFailed(Long id, Instant startedAt, Instant consumedAt) {
        jdbcTemplate.update("""
                update ingestion_job
                   set status = 'FAILED', failure_code = 'PROCESSING_FAILED',
                       started_at = ?, consumed_at = ?
                 where id = ?
                """, sqlTime(startedAt), sqlTime(consumedAt), id);
    }

    private void markExpired(Long id, Instant expiresAt) {
        jdbcTemplate.update("""
                update ingestion_job
                   set status = 'EXPIRED', expires_at = ?
                 where id = ?
                """, sqlTime(expiresAt), id);
    }

    private OffsetDateTime sqlTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private void assertState(Long id, IngestionJobStatus status, IngestionFailureCode failureCode) {
        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getFailureCode()).isEqualTo(failureCode);
    }

    private void assertPurged(PurgeFixture fixture) {
        assertThat(jobRepository.existsById(fixture.id())).isFalse();
        assertThat(uploadObjectRepository.existsById(fixture.key())).isFalse();
        assertThat(objectStorage.contains(fixture.key())).isFalse();
    }

    private record PurgeFixture(Long id, String key) {
    }
}
