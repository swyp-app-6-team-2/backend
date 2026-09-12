package com.star_pick.starpick.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingredient.service.IngredientNameIndex;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecipeDraftNormalizerTest {

    private final RecipeDraftNormalizer normalizer = new RecipeDraftNormalizer();
    private final IngredientNameIndex index = new IngredientNameIndex(Map.of("감자", 31L));

    @Test
    @DisplayName("문자열을 다듬고 빈 원소를 제거하며 재료 ID를 채운다")
    void trimsFiltersAndMatches() {
        RecipeDraft raw = new RecipeDraft(" 감자전 ", RecipeCategory.KOREAN, 30, 2,
                List.of(
                        new RecipeDraft.Ingredient(null, " 감 자 ", " 2개 "),
                        new RecipeDraft.Ingredient(null, "  ", "무시")),
                List.of(new RecipeDraft.Step(" 굽는다. "), new RecipeDraft.Step("  ")));

        RecipeDraft result = normalizer.normalize(raw, index);

        assertThat(result.title()).isEqualTo("감자전");
        assertThat(result.ingredients()).containsExactly(
                new RecipeDraft.Ingredient(31L, "감 자", "2개"));
        assertThat(result.steps()).containsExactly(new RecipeDraft.Step("굽는다."));
    }

    @Test
    @DisplayName("255자 문자열 상한과 숫자 범위를 정규화한다")
    void normalizesLengthsAndRanges() {
        RecipeDraft raw = new RecipeDraft("가".repeat(256), RecipeCategory.OTHER, 1441, 0,
                List.of(new RecipeDraft.Ingredient(null, "나".repeat(256), "다".repeat(256))),
                List.of(new RecipeDraft.Step("라".repeat(300))));

        RecipeDraft result = normalizer.normalize(raw, index);

        assertThat(result.title()).hasSize(255);
        assertThat(result.ingredients().getFirst().name()).hasSize(255);
        assertThat(result.ingredients().getFirst().amountText()).hasSize(255);
        assertThat(result.steps().getFirst().content()).hasSize(300);
        assertThat(result.cookTimeMinutes()).isNull();
        assertThat(result.servings()).isNull();
    }

    @Test
    @DisplayName("정규화 후 재료와 단계가 모두 없으면 결과를 버린다")
    void rejectsEmptyDraft() {
        RecipeDraft raw = new RecipeDraft("  ", null, null, null,
                List.of(new RecipeDraft.Ingredient(null, " ", null)),
                List.of(new RecipeDraft.Step(" ")));

        assertThat(normalizer.normalize(raw, index)).isNull();
    }

    @Test
    @DisplayName("null 배열도 빈 배열로 정규화한다")
    void normalizesNullLists() {
        RecipeDraft result = normalizer.normalize(
                new RecipeDraft(null, null, 1, 1, null, null), index);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("조리시간과 인분의 경계값을 유지하고 범위 밖 값은 null로 낮춘다")
    void normalizesNumericBoundaries() {
        RecipeDraft minimum = normalizer.normalize(new RecipeDraft(
                null, null, 1, 1, List.of(new RecipeDraft.Ingredient(null, "감자", null)), List.of()), index);
        RecipeDraft maximum = normalizer.normalize(new RecipeDraft(
                null, null, 1440, 1, List.of(), List.of(new RecipeDraft.Step("완성"))), index);
        RecipeDraft below = normalizer.normalize(new RecipeDraft(
                null, null, 0, 0, List.of(new RecipeDraft.Ingredient(null, "감자", null)), List.of()), index);

        assertThat(minimum.cookTimeMinutes()).isOne();
        assertThat(minimum.servings()).isOne();
        assertThat(maximum.cookTimeMinutes()).isEqualTo(1440);
        assertThat(below.cookTimeMinutes()).isNull();
        assertThat(below.servings()).isNull();
    }
}
