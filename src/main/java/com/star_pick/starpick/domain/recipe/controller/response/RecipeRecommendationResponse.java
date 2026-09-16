package com.star_pick.starpick.domain.recipe.controller.response;

import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RecipeRecommendationResponse(
        Long recipeId,
        String title,
        RecipeCategory category,
        @Schema(description = "대표 이미지 우선, 없으면 분석 원본 썸네일. 둘 다 없으면 null")
        String thumbnailUrl,
        @Schema(description = "저장된 재료명을 표시 순서대로 반환")
        List<String> mainIngredients
) {
}
