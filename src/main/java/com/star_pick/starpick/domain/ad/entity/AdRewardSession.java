package com.star_pick.starpick.domain.ad.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 로그인 사용자와 연결된 광고 시청 세션. REWARDED_AD_SSV.md §4.2, §6.
 *
 * <p>{@code id} 는 순차 사용자 ID 나 인증 토큰으로 만들지 않는다. 세션 소유자는 항상 {@code userId}
 * 로만 판정한다(§4.2). 상태 전이(지급·취소·만료)는 각자의 이슈가 구현하는 트랜잭션의 책임이라
 * 여기서는 발급 시점의 초기 상태({@code PENDING})만 만든다.
 */
@Entity
@Getter
@Table(name = "ad_reward_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdRewardSession implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AdRewardPlatform platform;

    @Column(name = "expected_ad_unit", nullable = false, updatable = false)
    private String expectedAdUnit;

    @Column(name = "reward_type", nullable = false, updatable = false)
    private String rewardType;

    @Column(name = "reward_amount", nullable = false, updatable = false)
    private int rewardAmount;

    @Column(name = "quota_date", nullable = false, updatable = false)
    private LocalDate quotaDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdRewardSessionStatus status;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "verification_deadline", nullable = false, updatable = false)
    private Instant verificationDeadline;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    private AdRewardSession(Long userId, String requestId, AdRewardPlatform platform, String expectedAdUnit,
            String rewardType, int rewardAmount, LocalDate quotaDate, Instant createdAt, Instant expiresAt,
            Instant verificationDeadline) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.expectedAdUnit = Objects.requireNonNull(expectedAdUnit, "expectedAdUnit");
        this.rewardType = Objects.requireNonNull(rewardType, "rewardType");
        this.rewardAmount = rewardAmount;
        this.quotaDate = Objects.requireNonNull(quotaDate, "quotaDate");
        this.status = AdRewardSessionStatus.PENDING;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.verificationDeadline = Objects.requireNonNull(verificationDeadline, "verificationDeadline");
    }

    /** 발급 API 가 사용자 잠금 안에서 호출한다. 예약 반영은 호출자가 daily_quota 에 함께 한다. */
    public static AdRewardSession issue(Long userId, String requestId, AdRewardPlatform platform,
            String expectedAdUnit, String rewardType, int rewardAmount, LocalDate quotaDate,
            Instant createdAt, Instant expiresAt, Instant verificationDeadline) {
        return new AdRewardSession(userId, requestId, platform, expectedAdUnit, rewardType, rewardAmount,
                quotaDate, createdAt, expiresAt, verificationDeadline);
    }

    /** SSV 검증을 통과했다. 지급 트랜잭션의 잠금 안에서만 부른다(§7). */
    public void markGranted(Instant grantedAt) {
        this.status = AdRewardSessionStatus.GRANTED;
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
    }

    /**
     * 검증 수신 마감을 넘긴 콜백이 도착해 더는 지급될 수 없다.
     *
     * <p>전용 시각 컬럼을 두지 않는다 — {@code status}·{@code reasonCode} 만으로 감사에 충분하고,
     * 별도 컬럼이 필요해지면 그건 취소·만료 이슈(06)가 스케줄 작업을 더하며 판단할 몫이다.
     */
    public void markExpired(String reasonCode) {
        this.status = AdRewardSessionStatus.EXPIRED;
        this.reasonCode = reasonCode;
    }

    /**
     * 사용자가 보상 청구를 포기했다. 이후 SSV 가 와도 자동 지급하지 않는다(§4.4).
     *
     * <p>{@code PENDING} 인 세션에만 부른다 — 이미 확정된 세션을 포기 요청으로 덮어쓰지 않는 것은
     * 호출자({@code AdRewardSessionService#cancelSession})의 책임이다.
     */
    public void markCancelled(String reasonCode, Instant cancelledAt) {
        this.status = AdRewardSessionStatus.CANCELLED;
        this.reasonCode = reasonCode;
        this.cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt");
    }

    /**
     * PK 를 서버가 직접 만들기 때문에 신규 여부를 따로 알려준다.
     *
     * <p>{@code UploadObject} 와 같은 이유다. 없으면 Spring Data 가 "id 가 이미 있으니 기존 행"으로
     * 판단해 {@code persist} 대신 {@code merge} 를 불러 발급마다 결과 없는 SELECT 가 헛돈다.
     */
    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
