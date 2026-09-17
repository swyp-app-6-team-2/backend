package com.star_pick.starpick.domain.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 선택 삭제 대상. MASTER 는 ingredientId, CUSTOM 은 customIngredientId 를 사용한다. */
public record DeleteIngredientItem(
        @NotNull(message = "재료 유형은 필수입니다.") IngredientType type,
        @NotNull(message = "재료 ID는 필수입니다.") @Positive(message = "재료 ID는 양수여야 합니다.") Long id
) { }
