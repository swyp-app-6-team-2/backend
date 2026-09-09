package com.star_pick.starpick.domain.recipe.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Recipe 애그리거트의 규칙. Spring Context 를 띄우지 않는다. */
class RecipeTest {

    private static final Long USER_ID = 1L;

    private Recipe manualRecipe() {
        return Recipe.createManual(USER_ID, "김치찌개", RecipeCategory.KOREAN, 30, 2, "조금 맵게");
    }

    @Test
    @DisplayName("직접 입력 생성은 등록 방식을 MANUAL 로 정한다")
    void createManualSetsRegistrationMethod() {
        Recipe recipe = manualRecipe();

        assertThat(recipe.getRegistrationMethod()).isEqualTo(RegistrationMethod.MANUAL);
        assertThat(recipe.getUserId()).isEqualTo(USER_ID);
        assertThat(recipe.getCoverImageKey()).isNull();
    }

    @Test
    @DisplayName("인분 수를 전달하지 않으면 1 이다")
    void servingsDefaultsToOne() {
        Recipe recipe = Recipe.createManual(USER_ID, "김치찌개", RecipeCategory.KOREAN, null, null, null);

        assertThat(recipe.getServings()).isEqualTo(1);
        assertThat(recipe.getCookTimeMinutes()).isNull();
        assertThat(recipe.getMemo()).isNull();
    }

    @Test
    @DisplayName("필수값이 null 이면 생성되지 않는다 — Bean Validation 이 먼저 거르는 프로그래머 오류 가드")
    void requiredFieldsAreGuarded() {
        assertThatThrownBy(() -> Recipe.createManual(null, "김치찌개", RecipeCategory.KOREAN, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Recipe.createManual(USER_ID, null, RecipeCategory.KOREAN, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Recipe.createManual(USER_ID, "김치찌개", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("재료는 전달 순서대로 0-base 표시 순서를 받는다")
    void ingredientsGetZeroBasedDisplayOrder() {
        Recipe recipe = manualRecipe();

        recipe.replaceIngredients(List.of(
                RecipeIngredient.of(null, "김치", "1/4포기"),
                RecipeIngredient.of(null, "두부", "1모"),
                RecipeIngredient.of(null, "대파", null)));

        assertThat(recipe.getIngredients())
                .extracting(RecipeIngredient::getName, RecipeIngredient::getDisplayOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("김치", 0),
                        org.assertj.core.groups.Tuple.tuple("두부", 1),
                        org.assertj.core.groups.Tuple.tuple("대파", 2));
    }

    @Test
    @DisplayName("조리 순서도 같은 규칙을 따른다")
    void stepsGetZeroBasedDisplayOrder() {
        Recipe recipe = manualRecipe();

        recipe.replaceSteps(List.of(RecipeStep.of("물을 끓인다"), RecipeStep.of("김치를 넣는다")));

        assertThat(recipe.getSteps())
                .extracting(RecipeStep::getContent, RecipeStep::getDisplayOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("물을 끓인다", 0),
                        org.assertj.core.groups.Tuple.tuple("김치를 넣는다", 1));
    }

    @Test
    @DisplayName("빈 목록으로 교체하면 전부 삭제된다")
    void replaceWithEmptyListClears() {
        Recipe recipe = manualRecipe();
        recipe.replaceIngredients(List.of(RecipeIngredient.of(null, "김치", null)));

        recipe.replaceIngredients(List.of());

        assertThat(recipe.getIngredients()).isEmpty();
    }

    @Test
    @DisplayName("다시 교체하면 표시 순서가 새로 매겨진다")
    void replaceRenumbers() {
        Recipe recipe = manualRecipe();
        recipe.replaceIngredients(List.of(
                RecipeIngredient.of(null, "김치", null),
                RecipeIngredient.of(null, "두부", null)));

        recipe.replaceIngredients(List.of(RecipeIngredient.of(null, "대파", null)));

        assertThat(recipe.getIngredients())
                .extracting(RecipeIngredient::getName, RecipeIngredient::getDisplayOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("대파", 0));
    }

    @Test
    @DisplayName("name과 amountText가 같아도 ingredientId가 바뀌면 다른 내용이다")
    void ingredientIdParticipatesInContentComparison() {
        Recipe recipe = manualRecipe();
        recipe.replaceIngredients(List.of(
                RecipeIngredient.of(null, "김치", "1/4포기")));
        RecipeIngredient before = recipe.getIngredients().get(0);

        recipe.replaceIngredients(List.of(
                RecipeIngredient.of(1L, "김치", "1/4포기")));

        assertThat(recipe.getIngredients().get(0)).isNotSameAs(before);
        assertThat(recipe.getIngredients().get(0).getIngredientId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("nullable 단일 필드는 null 로 제거된다")
    void nullableFieldsAreRemovedWithNull() {
        Recipe recipe = manualRecipe();

        recipe.changeMemo(null);
        recipe.changeCookTimeMinutes(null);

        assertThat(recipe.getMemo()).isNull();
        assertThat(recipe.getCookTimeMinutes()).isNull();
    }

    @Test
    @DisplayName("반환된 목록은 수정할 수 없다")
    void returnedCollectionsAreImmutable() {
        Recipe recipe = manualRecipe();

        assertThatThrownBy(() -> recipe.getIngredients().add(
                        RecipeIngredient.of(null, "김치", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
