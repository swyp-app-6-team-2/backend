package com.star_pick.starpick.domain.ingestion.domain;

import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import java.util.List;

public record RecipeDraft(
        String title,
        RecipeCategory categoryCode,
        Integer cookTimeMinutes,
        Integer servings,
        List<Ingredient> ingredients,
        List<Step> steps) {

    public record Ingredient(Long ingredientId, String name, String amountText) {
    }

    public record Step(String content) {
    }
}
