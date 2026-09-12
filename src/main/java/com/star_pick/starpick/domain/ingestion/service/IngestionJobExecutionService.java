package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class IngestionJobExecutionService {

    private final IngestionJobRepository repository;
    private final IngestionProperties properties;

    public IngestionJobExecutionService(IngestionJobRepository repository, IngestionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public List<PreemptedJob> preempt(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Instant now = Instant.now();
        return repository.findQueuedForUpdateSkipLocked(PageRequest.of(0, limit)).stream()
                .peek(job -> job.startProcessing(now))
                .map(job -> new PreemptedJob(job.getId(), job.getAttempt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<IngestionJobSnapshot> loadForProcessing(Long jobId, int attempt) {
        return repository.findById(jobId)
                .filter(job -> job.isCurrentAttempt(attempt))
                .map(IngestionJobSnapshot::from);
    }

    @Transactional
    public boolean saveResult(Long jobId, int attempt, RecipeDraft draft) {
        var job = repository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isCurrentAttempt(attempt)) {
            log.warn("늦게 끝난 결과를 버립니다. ingestionJobId={}, attempt={}", jobId, attempt);
            return false;
        }
        job.completeWithResult(draft, Instant.now().plus(properties.job().resultTtl()));
        return true;
    }

    @Transactional
    public boolean saveFailure(Long jobId, int attempt, IngestionFailureCode code) {
        var job = repository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isCurrentAttempt(attempt)) {
            log.warn("늦게 끝난 실패를 버립니다. ingestionJobId={}, attempt={}", jobId, attempt);
            return false;
        }
        job.fail(code);
        return true;
    }

    /**
     * 선점은 됐지만 실행 슬롯에 넘기지 못한 Job 을 대기로 되돌린다.
     *
     * <p>되돌리지 않으면 stale 기준 3분을 기다려야 하고, 그 사이 시도 예산 한 번이 소모된다
     * ({@code attempt} 가 2가 되면 다음 stale 은 복구 대신 실패다).
     *
     * @return 되돌렸으면 true. 이미 다른 경로가 상태를 바꿨으면 false
     */
    @Transactional
    public boolean releaseToQueued(Long jobId, int attempt) {
        return repository.releaseToQueued(jobId, attempt) == 1;
    }
}
