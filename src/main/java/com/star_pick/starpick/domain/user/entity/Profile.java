package com.star_pick.starpick.domain.user.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "profiles")
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private String nickname;

    // 이전 버전의 URL은 보존한다. 신규 이미지는 key만 저장한다.
    private String profileImageUrl;

    private String profileImageKey;

    public static Profile initial(User user) {
        return Profile.builder().user(user)
                .nickname("스타%04d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(10000)))
                .build();
    }

    public void changeNickname(String nickname) { this.nickname = nickname; }

    public void changeImage(String key) {
        this.profileImageKey = key;
        this.profileImageUrl = null;
    }

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
