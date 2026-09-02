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

    @Column(nullable = false)
    private boolean serviceTermsAgreed;

    @Column(nullable = false)
    private boolean privacyAgreed;

    @Column(nullable = false)
    private boolean marketingAgreed;

    @Column(nullable = false)
    private LocalDateTime signupCompletedAt;

    private LocalDateTime marketingAgreedAt;

    @Enumerated(EnumType.STRING)
    private Provider lastLoginProvider;

    private LocalDateTime lastLoginAt;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;

    public void updateLastLogin(Provider provider, LocalDateTime loginAt) {
        this.lastLoginProvider = provider;
        this.lastLoginAt = loginAt;
    }
}
