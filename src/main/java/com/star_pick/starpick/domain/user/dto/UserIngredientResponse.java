package com.star_pick.starpick.domain.user.dto;

import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import com.star_pick.starpick.domain.ingredient.domain.IngredientCategory;

/** 보유 재료 조회 및 등록 응답에서 공유하는 재료 항목. */
public record UserIngredientResponse(Long ingredientId, String name, IngredientCategory categoryCode, String iconUrl) {
    public static UserIngredientResponse from(Ingredient ingredient, String iconBaseUrl) {
        return new UserIngredientResponse(ingredient.getId(), ingredient.getName(), ingredient.getCategory(),
                iconBaseUrl + "/images/ingredients/" + ingredient.getIconKey() + ".webp");
    }
}
