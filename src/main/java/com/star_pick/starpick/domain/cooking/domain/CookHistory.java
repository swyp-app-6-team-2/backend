package com.star_pick.starpick.domain.cooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 한 번의 조리 완료 기록.
 *
 * <p>MVP 에 수정 API 가 없어 생성 이후 상태가 변하지 않는다. 그래서 {@code updatedAt} 도,
 * 상태를 바꾸는 행위 메서드도 두지 않는다.
 */
@Entity
@Getter
@Table(name = "cook_history",
        indexes = @Index(name = "idx_cook_history_recipe_id", columnList = "recipe_id"),
        uniqueConstraints = @UniqueConstraint(name = CookHistory.PHOTO_KEY_UNIQUE, columnNames = "photo_key"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CookHistory {

    /**
     * 제약 이름을 고정하는 이유: 위반을 잡아 409 로 번역할 때 이 이름으로 판별한다.
     * Hibernate 에 맡기면 해시 이름이 나와 분기가 조용히 깨진다.
     */
    public static final String PHOTO_KEY_UNIQUE = "uk_cook_history_photo_key";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 조리한 Recipe. 도메인 경계 규칙상 Recipe Entity 를 참조하지 않는 스칼라이고 FK 가 없다.
     * FK 는 migration 도구 도입(이슈 #11) 때 추가한다. 근거는 cooking.md §3.4.
     */
    @Column(nullable = false, updatable = false)
    private Long recipeId;

    /** 서버가 생성 요청을 처리한 시각. 클라이언트가 보낸 값을 쓰지 않는다. */
    @Column(nullable = false, updatable = false)
    private Instant cookedAt;

    /** Upload 가 발급한 저장소 객체 이름. null 이면 완성 사진이 없다. URL 이 아니라 Key 를 저장한다. */
    private String photoKey;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String memo;

    private CookHistory(Long recipeId, String photoKey, String memo) {
        this.recipeId = Objects.requireNonNull(recipeId, "recipeId");
        this.cookedAt = Instant.now();
        this.photoKey = photoKey;
        this.memo = memo;
    }

    /** 사진과 메모는 선택이다. 둘 다 없어도 기록을 남길 수 있다. */
    public static CookHistory create(Long recipeId, String photoKey, String memo) {
        return new CookHistory(recipeId, photoKey, memo);
    }
}
