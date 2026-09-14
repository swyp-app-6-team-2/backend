package com.star_pick.starpick.domain.user.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

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
    private LocalDateTime ageOver14AgreedAt;

    @Column(nullable = false)
    private boolean serviceTermsAgreed;

    @Column(nullable = false)
    private boolean privacyAgreed;

    @Column(nullable = false)
    private boolean marketingAgreed;

    @Column(nullable = false)
    private boolean serviceAgreed;

    private LocalDateTime serviceAgreedAt;

    @Column(nullable = false)
    private LocalDateTime signupCompletedAt;

    private LocalDateTime marketingAgreedAt;

    @Enumerated(EnumType.STRING)
    private Provider lastLoginProvider;

    private LocalDateTime lastLoginAt;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;

    @Column(name = "onboarding_completed_at")
    private LocalDateTime onboardingCompletedAt;

    public boolean isOnboardingRequired() {
        return onboardingCompletedAt == null;
    }

    public void completeOnboarding(LocalDateTime completedAt) {
        if (onboardingCompletedAt == null) {
            onboardingCompletedAt = completedAt;
        }
    }

    public void updateLastLogin(Provider provider, LocalDateTime loginAt) {
        this.lastLoginProvider = provider;
        this.lastLoginAt = loginAt;
    }

    public void updateLastActivity(LocalDateTime activityAt) {
        this.lastActivityAt = activityAt;
    }
}
