package com.star_pick.starpick.domain.recipe.controller.request;

import jakarta.validation.constraints.NotBlank;

/** 조리 순서 한 단계. 배열 순서가 표시 순서이며 내부 식별자와 displayOrder 는 요청으로 받지 않는다. */
public record RecipeStepRequest(
        @NotBlank(message = "조리 내용은 필수입니다.") String content
) {
}
