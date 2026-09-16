package com.star_pick.starpick.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomIngredientCreateRequest(
        @NotBlank(message = "재료명을 입력해주세요.")
        @Size(min = 1, max = 50, message = "재료명은 1자 이상 50자 이하로 입력해주세요.")
        String name
) {
    public CustomIngredientCreateRequest {
        name = name == null ? null : name.trim();
    }
}
