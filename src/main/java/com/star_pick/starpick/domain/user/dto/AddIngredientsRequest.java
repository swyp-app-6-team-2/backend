package com.star_pick.starpick.domain.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record AddIngredientsRequest(
        @NotEmpty(message = "재료를 1개 이상 선택해주세요.")
        List<@NotNull(message = "재료 ID는 필수입니다.") @Positive(message = "재료 ID는 양수여야 합니다.") Long> ingredientIds
) { }
