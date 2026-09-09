package com.star_pick.starpick.domain.recipe.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 재료 하나. 배열 순서가 표시 순서다. ingredientId 는 선택적인 마스터 참조이며,
 * RecipeIngredient PK와 displayOrder 는 요청으로 받지 않는다.
 *
 * <p>길이 상한 255 는 제품 결정이 아니라 varchar 기본 길이다. 이 값이 없으면 초과 입력이
 * flush 단계에서 터져 500 으로 나간다. 제품이 실제 상한을 정하면 그 값으로 바꾼다.
 */
public record RecipeIngredientRequest(
        Long ingredientId,

        @NotBlank(message = "재료명은 필수입니다.")
        @Size(max = 255, message = "재료명은 255자를 넘을 수 없습니다.") String name,

        @Size(max = 255, message = "재료 수량은 255자를 넘을 수 없습니다.") String amountText
) {
}
