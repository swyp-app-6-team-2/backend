package com.star_pick.starpick.domain.recipe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Recipe 에 저장된 재료. Recipe 애그리거트의 일부이며 독립 CRUD 를 제공하지 않는다.
 *
 * <p>{@code name} 은 저장 당시 사용자가 확인한 표현의 스냅샷이다. 공통 Ingredient 마스터와의
 * 연결({@code ingredientId})은 선택이며 마스터 이름과 독립적으로 보존한다.
 */
@Entity
@Getter
@Table(name = "recipe_ingredient",
        indexes = @Index(name = "idx_recipe_ingredient_recipe_id", columnList = "recipe_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    private Long ingredientId;

    @Column(nullable = false)
    private String name;

    private String amountText;

    /** 0-base. 요청 배열 인덱스와 같으며 외부에 노출하지 않는다. */
    @Column(nullable = false)
    private int displayOrder;

    private RecipeIngredient(Long ingredientId, String name, String amountText) {
        this.ingredientId = ingredientId;
        this.name = name;
        this.amountText = amountText;
    }

    public static RecipeIngredient of(Long ingredientId, String name, String amountText) {
        return new RecipeIngredient(ingredientId, name, amountText);
    }

    /** 표시 순서를 뺀 내용이 같은지. 교체 요청이 실제로 바꾸는 게 있는지 판단할 때 쓴다. */
    boolean hasSameContentAs(RecipeIngredient other) {
        return Objects.equals(this.ingredientId, other.ingredientId)
                && Objects.equals(this.name, other.name)
                && Objects.equals(this.amountText, other.amountText);
    }

    /** Recipe 만 호출한다. 소유 관계와 표시 순서는 Recipe 가 결정한다. */
    void attachTo(Recipe recipe, int displayOrder) {
        this.recipe = recipe;
        this.displayOrder = displayOrder;
    }
}
