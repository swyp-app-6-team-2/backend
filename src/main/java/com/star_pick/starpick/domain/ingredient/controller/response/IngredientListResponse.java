package com.star_pick.starpick.domain.ingredient.controller.response;

import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import com.star_pick.starpick.domain.ingredient.domain.IngredientCategory;
import java.util.Arrays;
import java.util.List;

public record IngredientListResponse(List<IngredientResponse> ingredients) {

    public static IngredientListResponse from(List<Ingredient> ingredients) {
        return new IngredientListResponse(
                ingredients.stream().map(IngredientResponse::from).toList());
    }

    public record IngredientResponse(
            Long ingredientId,
            String code,
            String name,
            IngredientCategory categoryCode,
            List<String> aliases
    ) {
        private static IngredientResponse from(Ingredient ingredient) {
            return new IngredientResponse(
                    ingredient.getId(),
                    ingredient.getCode(),
                    ingredient.getName(),
                    ingredient.getCategory(),
                    Arrays.stream(ingredient.getAliases()).toList());
        }
    }
}
