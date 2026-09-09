package com.star_pick.starpick.domain.recipe.repository;

/**
 * 목록 조회에서 재료명만 뽑을 때 쓰는 조회 전용 행.
 *
 * <p>RecipeIngredient Entity 를 읽지 않는다. 목록은 이름만 필요한데 Entity 를 읽으면 지연 로딩
 * 컬렉션이 딸려와 트랜잭션 밖 조립이 불가능해진다.
 */
public record RecipeIngredientNameRow(Long recipeId, String name) {
}
