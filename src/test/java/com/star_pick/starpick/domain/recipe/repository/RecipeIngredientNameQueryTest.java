package com.star_pick.starpick.domain.recipe.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 목록 조회의 재료명 일괄 조회가 소유자로 걸러지는지 확인한다.
 *
 * <p>현재 유일한 호출부인 {@code RecipeService.getRecipes} 는 이미 소유자로 거른 ID 만 넘기므로
 * 이 조건이 사라져도 API 테스트는 전부 통과한다. 다른 호출부가 생겼을 때 남의 재료명이 새는 것을
 * 막는 방어선이라 Repository 수준에서 직접 확인한다.
 */
@IntegrationTest
class RecipeIngredientNameQueryTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        recipeRepository.deleteAll();
    }

    @Test
    @DisplayName("다른 사용자의 Recipe ID 를 넘겨도 재료명을 돌려주지 않는다")
    void excludesOtherUsersIngredients() {
        Long otherRecipeId = fixtures.saveRecipeWithChildren(OTHER_ID);

        List<RecipeIngredientNameRow> rows =
                recipeRepository.findIngredientNames(OWNER_ID, List.of(otherRecipeId));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("소유한 Recipe 의 재료명은 표시 순서대로 돌려준다")
    void returnsOwnIngredientsInDisplayOrder() {
        Long recipeId = fixtures.saveRecipeWithChildren(OWNER_ID);

        List<RecipeIngredientNameRow> rows =
                recipeRepository.findIngredientNames(OWNER_ID, List.of(recipeId));

        assertThat(rows).extracting(RecipeIngredientNameRow::name)
                .containsExactly("김치", "두부");
    }
}
