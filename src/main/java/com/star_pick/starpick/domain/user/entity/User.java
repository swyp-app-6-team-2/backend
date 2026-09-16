package com.star_pick.starpick.domain.user.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    // 기존 회원은 확인 이력이 없으므로 null. 신규 가입은 true만 허용한다.
    @Column(name = "age_over_14_agreed")
    private Boolean ageOver14Agreed;

    @Column(name = "age_over_14_agreed_at")
    private Instant ageOver14AgreedAt;

    @Column(nullable = false)
    private boolean serviceTermsAgreed;

    @Column(nullable = false)
    private boolean privacyAgreed;

    @Column(nullable = false)
    private boolean marketingAgreed;

    @Column(nullable = false)
    private boolean serviceAgreed;

    private Instant serviceAgreedAt;

    @Column(nullable = false)
    private Instant signupCompletedAt;

    private Instant marketingAgreedAt;

    @Builder.Default
    @Column(nullable = false)
    private int recipeSlotLimit = 10;   // 최대 저장 슬롯 (무료 10 + 광고당 +2)

    @Builder.Default
    @Column(nullable = false)
    private int cumulativeRecipeCount = 0;  // 삭제 포함, 역대 전체 등록 횟수

    @Enumerated(EnumType.STRING)
    private Provider lastLoginProvider;

    private Instant lastLoginAt;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @CreationTimestamp
    @Column(nullable = false)
    private Instant createdAt;

    private Instant deletedAt;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    public void beginWithdrawal(Instant now) {
        if (deletedAt == null) deletedAt = now;
    }

    public boolean isOnboardingRequired() {
        return onboardingCompletedAt == null;
    }

    public void completeOnboarding(Instant completedAt) {
        if (onboardingCompletedAt == null) {
            onboardingCompletedAt = completedAt;
        }
    }

    public void updateLastLogin(Provider provider, Instant loginAt) {
        this.lastLoginProvider = provider;
        this.lastLoginAt = loginAt;
    }

    public void updateLastActivity(Instant activityAt) {
        this.lastActivityAt = activityAt;
    }

    public int getRemainingRecipeSlots() {
        return recipeSlotLimit - cumulativeRecipeCount;
    }

    public void recordRecipeCreated() {
        this.cumulativeRecipeCount++;
    }

    public void increaseRecipeSlotLimit(int amount) {
        this.recipeSlotLimit += amount;
    }
}
