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
@Table(
        name = "social_credentials",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_provider_social_uid", columnNames = {"provider", "social_uid"}),
                @UniqueConstraint(name = "uk_user_provider", columnNames = {"user_id", "provider"})
})
public class SocialCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(nullable = false)
    private String socialUid;

    @Column(nullable = false, updatable = false)
    private String email;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime createdAt;

}
