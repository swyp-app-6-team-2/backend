package com.star_pick.starpick.domain.ad.repository;

import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdRewardSessionRepository extends JpaRepository<AdRewardSession, UUID> {

    /** 잠금 순서 {@code users → daily_quota → session} (§7)의 마지막 단계. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AdRewardSession s where s.id = :id")
    Optional<AdRewardSession> findByIdForUpdate(@Param("id") UUID id);

    /** 발급 멱등 키 조회(§4.2). 같은 키면 기존 세션을 반환하고, 플랫폼이 다르면 409 대상이다. */
    Optional<AdRewardSession> findByUserIdAndRequestId(Long userId, String requestId);

    /** 본인 세션만 조회한다(§4.3, §4.4). 없거나 다른 사용자 것이면 동일한 404 대상이다. */
    Optional<AdRewardSession> findByIdAndUserId(UUID id, Long userId);

    /** 날짜별 사용자당 진행 중 세션 1개 제한 확인(§2.1)에 쓴다. */
    List<AdRewardSession> findByUserIdAndQuotaDateAndStatus(Long userId, LocalDate quotaDate,
            AdRewardSessionStatus status);

    /**
     * 날짜와 무관하게 진행 중인 세션 전체. 상태 조회(§4.1)가 전날의 미완료 세션까지 복구용으로
     * 돌려줘야 해서 날짜로 좁히지 않는다.
     */
    List<AdRewardSession> findByUserIdAndStatus(Long userId, AdRewardSessionStatus status);

    /**
     * 검증 수신 마감을 넘긴 {@code PENDING} 세션 후보(§2.1, §6). id 만 돌려주고 건별로 잠근 뒤
     * 처리한다 — 예약 반환이 daily_quota 도 함께 바꿔야 해서 단일 bulk UPDATE 로 끝낼 수 없다
     * ({@code IngestionJobRepository#findPurgeTargetIds} 와 같은 이유).
     */
    @Query("""
            select s.id from AdRewardSession s
             where s.status = com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus.PENDING
               and s.verificationDeadline < :now
             order by s.verificationDeadline asc
            """)
    List<UUID> findExpiredCandidateIds(@Param("now") Instant now, Pageable pageable);
}
