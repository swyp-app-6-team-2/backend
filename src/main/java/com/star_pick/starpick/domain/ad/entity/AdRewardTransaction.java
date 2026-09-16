package com.star_pick.starpick.domain.ad.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Google SSV 콜백 한 건의 검증·처리 결과. REWARDED_AD_SSV.md §6.
 *
 * <p>{@code sessionId}/{@code userId} 는 세션을 특정하지 못한 콜백에 한해 null 을 허용한다. 지급
 * 금액·시각과 상태의 조합은 {@code ck_ad_reward_transaction_granted_amount} DB CHECK 가 최종
 * 방어선이라 두 팩토리만으로 그 조합을 벗어나지 않게 한다.
 */
@Entity
@Getter
@Table(name = "ad_reward_transaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdRewardTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AdRewardTransactionStatus status;

    @Column(name = "reason_code", updatable = false)
    private String reasonCode;

    @Column(name = "ad_unit", nullable = false, updatable = false)
    private String adUnit;

    @Column(name = "reward_item", nullable = false, updatable = false)
    private String rewardItem;

    @Column(name = "received_reward_amount", nullable = false, updatable = false)
    private int receivedRewardAmount;

    @Column(name = "reward_event_at", nullable = false, updatable = false)
    private Instant rewardEventAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Column(name = "granted_amount", nullable = false, updatable = false)
    private int grantedAmount;

    @Column(name = "granted_at", updatable = false)
    private Instant grantedAt;

    private AdRewardTransaction(String transactionId, UUID sessionId, Long userId,
            AdRewardTransactionStatus status, String reasonCode, String adUnit, String rewardItem,
            int receivedRewardAmount, Instant rewardEventAt, Instant receivedAt, Instant processedAt,
            int grantedAmount, Instant grantedAt) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.sessionId = sessionId;
        this.userId = userId;
        this.status = Objects.requireNonNull(status, "status");
        this.reasonCode = reasonCode;
        this.adUnit = Objects.requireNonNull(adUnit, "adUnit");
        this.rewardItem = Objects.requireNonNull(rewardItem, "rewardItem");
        this.receivedRewardAmount = receivedRewardAmount;
        this.rewardEventAt = Objects.requireNonNull(rewardEventAt, "rewardEventAt");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
        this.grantedAmount = grantedAmount;
        this.grantedAt = grantedAt;
    }

    /** 검증을 통과해 슬롯을 지급한 거래. {@code grantedAmount > 0} 이고 {@code grantedAt} 이 있어야 한다. */
    public static AdRewardTransaction granted(String transactionId, UUID sessionId, Long userId,
            String adUnit, String rewardItem, int receivedRewardAmount,
            Instant rewardEventAt, Instant receivedAt, Instant processedAt,
            int grantedAmount, Instant grantedAt) {
        if (grantedAmount <= 0) {
            throw new IllegalArgumentException("grantedAmount 는 0보다 커야 한다: " + grantedAmount);
        }
        return new AdRewardTransaction(transactionId, sessionId, userId, AdRewardTransactionStatus.GRANTED,
                null, adUnit, rewardItem, receivedRewardAmount, rewardEventAt, receivedAt, processedAt,
                grantedAmount, Objects.requireNonNull(grantedAt, "grantedAt"));
    }

    /** 서명은 유효했으나 업무 검증에서 거절한 거래. 지급하지 않는다. */
    public static AdRewardTransaction rejected(String transactionId, UUID sessionId, Long userId,
            String adUnit, String rewardItem, int receivedRewardAmount,
            Instant rewardEventAt, Instant receivedAt, Instant processedAt, String reasonCode) {
        return new AdRewardTransaction(transactionId, sessionId, userId, AdRewardTransactionStatus.REJECTED,
                Objects.requireNonNull(reasonCode, "reasonCode"), adUnit, rewardItem, receivedRewardAmount,
                rewardEventAt, receivedAt, processedAt, 0, null);
    }
}
