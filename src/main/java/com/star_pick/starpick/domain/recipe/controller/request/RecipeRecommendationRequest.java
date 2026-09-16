package com.star_pick.starpick.domain.recipe.controller.request;

import com.star_pick.starpick.domain.recipe.domain.RecommendationMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RecipeRecommendationRequest(
        @NotNull(message = "추천 방식은 필수입니다.")
        RecommendationMode recommendationMode,
        @Schema(description = "재추천 시 제외할 직전 레시피 ID")
        @Positive(message = "직전 레시피 ID는 양수여야 합니다.")
        Long previousRecipeId
) {
}
