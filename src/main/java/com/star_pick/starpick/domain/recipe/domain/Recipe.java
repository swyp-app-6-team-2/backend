package com.star_pick.starpick.domain.recipe.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.ObjIntConsumer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 사용자가 최종 저장한 Recipe. 재료와 조리 순서를 소유하는 애그리거트 루트다.
 *
 * <p>소유자는 {@code userId} 스칼라로만 갖는다. 다른 도메인의 JPA Entity 를 직접 참조하지
 * 않는다는 규칙(CLAUDE.md §4) 때문이며, 그 결과 DB 에 user FK 가 없다.
 *
 * <p>사용자 입력 검증은 Request DTO 의 Bean Validation 이 담당한다. 여기 있는 검사는
 * 프로그래머 오류를 잡는 마지막 방어선이고, 걸리면 500 이 나가는 것이 맞다.
 */
@Entity
@Getter
@Table(name = "recipe")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recipe {

    private static final int DEFAULT_SERVINGS = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecipeCategory categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RegistrationMethod registrationMethod;

    /** Upload 가 발급한 저장소 객체 이름. null 이면 대표 이미지가 없다. URL 이 아니라 Key 를 저장한다. */
    private String coverImageKey;

    private Integer cookTimeMinutes;

    @Column(nullable = false)
    private int servings;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String memo;

    // Hibernate 가 로딩 시 PersistentBag 으로 교체하므로 final 로 둘 수 없다.
    // 대신 replace* 안에서 인스턴스를 재대입하지 않는다. 재대입하면 orphanRemoval 이
    // "A collection with cascade=all-delete-orphan was no longer referenced" 로 터진다.
    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<RecipeIngredient> ingredients = new ArrayList<>();

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<RecipeStep> steps = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Recipe(Long userId, String title, RecipeCategory categoryCode,
                   Integer cookTimeMinutes, Integer servings, String memo) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.title = Objects.requireNonNull(title, "title");
        this.categoryCode = Objects.requireNonNull(categoryCode, "categoryCode");
        this.registrationMethod = RegistrationMethod.MANUAL;
        this.cookTimeMinutes = cookTimeMinutes;
        this.servings = servings == null ? DEFAULT_SERVINGS : servings;
        this.memo = memo;
    }

    /** 직접 입력으로 생성한다. 등록 방식은 서버가 MANUAL 로 결정한다. */
    public static Recipe createManual(Long userId, String title, RecipeCategory categoryCode,
                                      Integer cookTimeMinutes, Integer servings, String memo) {
        return new Recipe(userId, title, categoryCode, cookTimeMinutes, servings, memo);
    }

    public List<RecipeIngredient> getIngredients() {
        return List.copyOf(ingredients);
    }

    public List<RecipeStep> getSteps() {
        return List.copyOf(steps);
    }

    public void changeTitle(String title) {
        this.title = Objects.requireNonNull(title, "title");
        markUpdated();
    }

    public void changeCategory(RecipeCategory categoryCode) {
        this.categoryCode = Objects.requireNonNull(categoryCode, "categoryCode");
        markUpdated();
    }

    /** null 이면 조리 시간을 제거한다. */
    public void changeCookTimeMinutes(Integer cookTimeMinutes) {
        this.cookTimeMinutes = cookTimeMinutes;
        markUpdated();
    }

    public void changeServings(int servings) {
        this.servings = servings;
        markUpdated();
    }

    /** null 이면 메모를 제거한다. */
    public void changeMemo(String memo) {
        this.memo = memo;
        markUpdated();
    }

    /**
     * null 이면 대표 이미지를 제거한다.
     *
     * <p>Key 의 유효성(소유자·용도·업로드 완료·미연결)은 Upload 가 판단한다. 여기서는 이미
     * 검증된 값을 받는다고 본다.
     */
    public void changeCoverImage(String coverImageKey) {
        this.coverImageKey = coverImageKey;
        markUpdated();
    }

    /** 전달된 목록으로 전체 교체한다. 빈 목록이면 전부 삭제된다. */
    public void replaceIngredients(List<RecipeIngredient> newIngredients) {
        replaceChildren(this.ingredients, newIngredients,
                RecipeIngredient::hasSameContentAs,
                (ingredient, order) -> ingredient.attachTo(this, order));
    }

    /** 전달된 목록으로 전체 교체한다. 빈 목록이면 전부 삭제된다. */
    public void replaceSteps(List<RecipeStep> newSteps) {
        replaceChildren(this.steps, newSteps,
                RecipeStep::hasSameContentAs,
                (step, order) -> step.attachTo(this, order));
    }

    /**
     * 자식 컬렉션을 통째로 교체한다.
     *
     * <p>내용과 순서가 그대로면 아무것도 하지 않는다. 수정 화면이 폼 전체를 다시 보내는 흔한
     * 패턴에서, 바뀐 게 없는데도 자식 수에 비례해 delete/insert 가 나가는 것을 막는다.
     *
     * <p>대상 리스트는 인스턴스를 재대입하지 않고 제자리에서 비운다. 재대입하면 orphanRemoval 이
     * "A collection with cascade=all-delete-orphan was no longer referenced" 로 터진다.
     */
    private <T> void replaceChildren(List<T> target, List<T> source,
                                     BiPredicate<T, T> sameContent, ObjIntConsumer<T> attach) {
        if (hasSameOrder(target, source, sameContent)) {
            return;
        }
        target.clear();
        for (int order = 0; order < source.size(); order++) {
            T child = source.get(order);
            attach.accept(child, order);
            target.add(child);
        }
        markUpdated();
    }

    private static <T> boolean hasSameOrder(List<T> current, List<T> candidate, BiPredicate<T, T> sameContent) {
        if (current.size() != candidate.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (!sameContent.test(current.get(i), candidate.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * updatedAt 을 갱신한다.
     *
     * <p>@UpdateTimestamp 는 Recipe 행 자체가 dirty 일 때만 동작한다. 자식만 교체한 수정은
     * Recipe 행이 바뀌지 않아 갱신되지 않으므로 여기서 직접 dirty 를 만든다.
     */
    private void markUpdated() {
        this.updatedAt = LocalDateTime.now();
    }
}
