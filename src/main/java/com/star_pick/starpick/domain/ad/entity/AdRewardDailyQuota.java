package com.star_pick.starpick.domain.ad.entity;

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

    /**
     * 시청 세션 발급 시 그 날짜의 이용 가능 횟수 1개를 예약한다.
     *
     * <p>한도 검사는 하지 않는다. 호출자(세션 발급 서비스)가 {@code AdRewardProperties.dailyLimit}
     * 로 먼저 확인한 뒤 잠금 안에서 부른다 — 정책값이 바뀔 수 있어 Entity 가 상수를 들고 있지 않는다.
     * {@code ck_ad_reward_daily_quota_limit} DB CHECK 가 최종 방어선이다.
     */
    public void reserve() {
        this.reservedCount++;
    }

    /** SSV 검증을 통과해 예약을 성공 지급으로 확정한다. 지급 트랜잭션의 잠금 안에서만 부른다(§7). */
    public void grant() {
        this.reservedCount--;
        this.grantedCount++;
    }

    /** 세션이 더는 지급될 수 없게 되어(예: 검증 마감 경과) 예약만 반환한다. 지급 없이 횟수만 되돌린다. */
    public void release() {
        this.reservedCount--;
    }
}
