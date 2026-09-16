package com.star_pick.starpick.domain.ad.repository;

import com.star_pick.starpick.domain.ad.entity.AdRewardDailyQuota;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdRewardDailyQuotaRepository extends JpaRepository<AdRewardDailyQuota, Long> {

    /**
     * 잠금 순서 {@code users → daily_quota → session} (§7)의 두 번째 단계. 사용자 행을 먼저 잠근
     * 뒤 호출한다. 행이 없으면 그 날짜의 첫 시도이므로 호출자가 {@link AdRewardDailyQuota#create}
     * 로 만들어 저장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from AdRewardDailyQuota q where q.userId = :userId and q.quotaDate = :quotaDate")
    Optional<AdRewardDailyQuota> findForUpdate(@Param("userId") Long userId, @Param("quotaDate") LocalDate quotaDate);

    Optional<AdRewardDailyQuota> findByUserIdAndQuotaDate(Long userId, LocalDate quotaDate);
}
