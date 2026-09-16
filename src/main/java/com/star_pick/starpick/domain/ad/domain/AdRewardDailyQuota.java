package com.star_pick.starpick.domain.ad.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자·KST 날짜별 광고 보상 지급/예약 횟수. REWARDED_AD_SSV.md §6.
 *
 * <p>지급·예약 증감은 이 이슈의 범위가 아니다(세션 발급·SSV 지급·취소 이슈가 각자의 트랜잭션에서
 * 수행한다). 여기서는 초기 행 생성만 제공한다. 잠금은 {@code AdRewardDailyQuotaRepository#findForUpdate}가
 * 담당하며, 호출 순서는 {@code users → daily_quota → session} 다(§7).
 */
@Entity
@Getter
@Table(name = "ad_reward_daily_quota")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdRewardDailyQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "quota_date", nullable = false, updatable = false)
    private LocalDate quotaDate;

    @Column(name = "granted_count", nullable = false)
    private int grantedCount;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AdRewardDailyQuota(Long userId, LocalDate quotaDate, Instant createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.quotaDate = Objects.requireNonNull(quotaDate, "quotaDate");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.grantedCount = 0;
        this.reservedCount = 0;
    }

    /** 그 날짜의 첫 시청 시도에서 사용자 잠금 안에 만드는 초기 행. */
    public static AdRewardDailyQuota create(Long userId, LocalDate quotaDate, Instant createdAt) {
        return new AdRewardDailyQuota(userId, quotaDate, createdAt);
    }
}
