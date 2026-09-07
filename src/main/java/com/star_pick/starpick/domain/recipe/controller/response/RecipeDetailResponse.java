package com.star_pick.starpick.domain.recipe.controller.response;

import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import java.util.List;

/**
 * Recipe 상세 조회 응답.
 *
 * <p>{@code @JsonInclude(NON_NULL)} 을 붙이지 않는다. 값이 없는 단일 필드도 키가 null 로
 * 존재해야 한다는 것이 응답 계약이다(02-0). 목록은 없으면 빈 배열이다.
 *
 * <p>Ingredient·Step 의 내부 식별자와 표시 순서는 노출하지 않는다.
 *
 * <p>{@code coverImageUrl} 은 이미지 업로드 단계, {@code source} 는 Ingestion 단계에서 채워진다.
 * 그때까지 MANUAL Recipe 만 존재하므로 둘 다 항상 null 이다.
 */
public record RecipeDetailResponse(
        Long recipeId,
        String title,
        RecipeCategory categoryCode,
        String coverImageUrl,
        Integer cookTimeMinutes,
        int servings,
        String memo,
        List<RecipeIngredientResponse> ingredients,
        List<RecipeStepResponse> steps,
        RecipeSourceResponse source
) {

    public record RecipeIngredientResponse(String name, String amountText) {
    }

    public record RecipeStepResponse(String content) {
    }

    public record RecipeSourceResponse(String sourceType, String originalUrl) {
    }

    /** 트랜잭션 안에서 호출해야 한다. open-in-view 가 꺼져 있어 지연 로딩 컬렉션을 밖에서 읽을 수 없다. */
    public static RecipeDetailResponse from(Recipe recipe) {
        return new RecipeDetailResponse(
                recipe.getId(),
                recipe.getTitle(),
                recipe.getCategoryCode(),
                null,
                recipe.getCookTimeMinutes(),
                recipe.getServings(),
                recipe.getMemo(),
                recipe.getIngredients().stream()
                        .map(ingredient -> new RecipeIngredientResponse(
                                ingredient.getName(), ingredient.getAmountText()))
                        .toList(),
                recipe.getSteps().stream()
                        .map(step -> new RecipeStepResponse(step.getContent()))
                        .toList(),
                null);
    }
}
