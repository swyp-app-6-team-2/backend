package com.star_pick.starpick.domain.recipe.service;

/** {@code created} 가 false 면 같은 IngestionJob 으로 이미 만든 Recipe 를 돌려준 것이다(200). */
public record RecipeCreateResult(Long recipeId, boolean created) {
}
