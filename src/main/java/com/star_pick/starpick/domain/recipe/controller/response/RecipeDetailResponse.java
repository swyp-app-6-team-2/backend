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
 * <p>RecipeIngredient·Step 의 PK와 표시 순서는 노출하지 않는다. {@code ingredientId}는
 * RecipeIngredient PK가 아니라 앱이 PATCH에서 다시 보낼 마스터 참조이므로 노출한다.
 *
 * <p>{@code coverImageUrl} 은 저장된 Key 로 만든 조회용 서명 URL 이다. 대표 이미지가 없거나
 * 서명에 실패하면 null 이다. {@code source} 는 Ingestion 단계에서 채워지며 그때까지 항상 null 이다.
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

    public record RecipeIngredientResponse(Long ingredientId, String name, String amountText) {
    }

    public record RecipeStepResponse(String content) {
    }

    public record RecipeSourceResponse(String sourceType, String originalUrl) {
    }

    /**
     * 트랜잭션 안에서 호출해야 한다. open-in-view 가 꺼져 있어 지연 로딩 컬렉션을 밖에서 읽을 수 없다.
     *
     * <p>{@code coverImageUrl} 을 인자로 받는 이유: 조회용 URL 을 만들려면 Upload 를 호출해야
     * 하는데, 응답 DTO 가 다른 도메인의 Service 를 호출하지 않도록 Service 에서 만들어 넘긴다.
     */
    public static RecipeDetailResponse from(Recipe recipe, String coverImageUrl) {
        return new RecipeDetailResponse(
                recipe.getId(),
                recipe.getTitle(),
                recipe.getCategoryCode(),
                coverImageUrl,
                recipe.getCookTimeMinutes(),
                recipe.getServings(),
                recipe.getMemo(),
                recipe.getIngredients().stream()
                        .map(ingredient -> new RecipeIngredientResponse(
                                ingredient.getIngredientId(),
                                ingredient.getName(), ingredient.getAmountText()))
                        .toList(),
                recipe.getSteps().stream()
                        .map(step -> new RecipeStepResponse(step.getContent()))
                        .toList(),
                null);
    }
}
