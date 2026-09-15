package com.star_pick.starpick.domain.user.dto;

import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import com.star_pick.starpick.domain.ingredient.domain.IngredientCategory;
import java.util.List;

public record AddIngredientsResponse(List<Item> ingredients) {
    public record Item(Long ingredientId, String name, IngredientCategory categoryCode, String iconUrl) {
        public static Item from(Ingredient ingredient, String iconBaseUrl) {
            return new Item(ingredient.getId(), ingredient.getName(), ingredient.getCategory(),
                    iconBaseUrl + "/images/ingredients/" + ingredient.getIconKey() + ".webp");
        }
    }
}
