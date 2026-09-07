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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Recipe 의 조리 순서 한 단계. Recipe 애그리거트의 일부이며 독립 CRUD 를 제공하지 않는다. */
@Entity
@Getter
@Table(name = "recipe_step",
        indexes = @Index(name = "idx_recipe_step_recipe_id", columnList = "recipe_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String content;

    /** 0-base. 요청 배열 인덱스와 같으며 외부에 노출하지 않는다. */
    @Column(nullable = false)
    private int displayOrder;

    private RecipeStep(String content) {
        this.content = content;
    }

    public static RecipeStep of(String content) {
        return new RecipeStep(content);
    }

    /** 표시 순서를 뺀 내용이 같은지. 교체 요청이 실제로 바꾸는 게 있는지 판단할 때 쓴다. */
    boolean hasSameContentAs(RecipeStep other) {
        return Objects.equals(this.content, other.content);
    }

    /** Recipe 만 호출한다. 소유 관계와 표시 순서는 Recipe 가 결정한다. */
    void attachTo(Recipe recipe, int displayOrder) {
        this.recipe = recipe;
        this.displayOrder = displayOrder;
    }
}
