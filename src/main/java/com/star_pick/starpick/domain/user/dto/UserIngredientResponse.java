package com.star_pick.starpick.domain.user.dto;

import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import com.star_pick.starpick.domain.ingredient.domain.IngredientCategory;
import com.star_pick.starpick.domain.user.entity.UserCustomIngredient;

/** 보유 재료 조회 및 등록 응답에서 공유하는 재료 항목. 마스터와 커스텀 재료를 같은 형태로 표현한다. */
public record UserIngredientResponse(IngredientType ingredientType, Long ingredientId, Long customIngredientId,
        String name, IngredientCategory categoryCode, String iconUrl) {

    public static UserIngredientResponse from(Ingredient ingredient, String iconBaseUrl) {
        return new UserIngredientResponse(IngredientType.MASTER, ingredient.getId(), null, ingredient.getName(),
                ingredient.getCategory(), iconBaseUrl + "/images/ingredients/" + ingredient.getIconKey() + ".webp");
    }

    public static UserIngredientResponse fromCustom(UserCustomIngredient custom) {
        return new UserIngredientResponse(IngredientType.CUSTOM, null, custom.getId(), custom.getName(), null, null);
    }
}
