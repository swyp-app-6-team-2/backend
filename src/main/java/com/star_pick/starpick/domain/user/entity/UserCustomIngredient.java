package com.star_pick.starpick.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 이름만 입력해 등록한 본인 전용 재료. 공통 재료 마스터와 무관하며 중복 등록을 허용한다.
 */
@Entity
@Getter
@Table(name = "user_custom_ingredient")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCustomIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private UserCustomIngredient(Long userId, String name) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.name = Objects.requireNonNull(name, "name");
        this.createdAt = Instant.now();
    }

    public static UserCustomIngredient create(Long userId, String name) {
        return new UserCustomIngredient(userId, name);
    }
}
