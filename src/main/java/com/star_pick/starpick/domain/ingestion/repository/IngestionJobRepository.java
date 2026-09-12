package com.star_pick.starpick.domain.ingestion.repository;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, Long> {

    Optional<IngestionJob> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select j from IngestionJob j
            where j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.QUEUED
            order by j.id asc
            """)
    List<IngestionJob> findQueuedForUpdateSkipLocked(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from IngestionJob j where j.id = :id")
    Optional<IngestionJob> findByIdForUpdate(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("""
            update IngestionJob j
               set j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.QUEUED
             where j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.PROCESSING
               and j.attempt = 1
               and j.startedAt < :threshold
            """)
    int requeueStale(@Param("threshold") Instant threshold);

    /**
     * 선점했지만 실행에 넘기지 못한 Job 을 곧바로 대기로 되돌린다.
     *
     * <p>이게 없으면 stale 복구(3분)를 기다려야 하고, 그동안 시도 예산 한 번을 헛되게 쓴다.
     * {@code attempt} 는 되돌리지 않는다 — 선점 횟수는 실제로 늘었고, 늦게 끝난 결과를 버리는
     * 기준이므로 되돌리면 이미 무효가 된 시도의 결과가 다시 유효해진다.
     *
     * <p>해당 시도가 아직 유효할 때만 바꾼다. 조건이 어긋나면 stale 복구나 다른 경로가 이미
     * 처리한 것이므로 아무것도 하지 않는다.
     */
    @Transactional
    @Modifying
    @Query("""
            update IngestionJob j
               set j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.QUEUED
             where j.id = :id
               and j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.PROCESSING
               and j.attempt = :attempt
            """)
    int releaseToQueued(@Param("id") Long id, @Param("attempt") int attempt);

    @Transactional
    @Modifying
    @Query("""
            update IngestionJob j
               set j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.FAILED,
                   j.failureCode = com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode.PROCESSING_FAILED
             where j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.PROCESSING
               and j.attempt >= 2
               and j.startedAt < :threshold
            """)
    int failStale(@Param("threshold") Instant threshold);

    @Transactional
    @Modifying
    @Query("""
            update IngestionJob j
               set j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.FAILED,
                   j.failureCode = com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode.PROCESSING_FAILED
             where j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.QUEUED
               and j.createdAt < :threshold
            """)
    int failStuckQueued(@Param("threshold") Instant threshold);

    @Transactional
    @Modifying
    @Query("""
            update IngestionJob j
               set j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.EXPIRED,
                   j.result = null
             where j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.RESULT_READY
               and j.consumedAt is null
               and j.expiresAt <= :now
            """)
    int expireResults(@Param("now") Instant now);

    @Query("""
            select j.id from IngestionJob j
             where j.consumedAt is null
               and ((j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.FAILED
                     and coalesce(j.startedAt, j.createdAt) < :threshold)
                 or (j.status = com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus.EXPIRED
                     and j.expiresAt < :threshold))
             order by j.id asc
            """)
    List<Long> findPurgeTargetIds(@Param("threshold") Instant threshold, Pageable pageable);
}
