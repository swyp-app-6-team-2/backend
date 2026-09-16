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
