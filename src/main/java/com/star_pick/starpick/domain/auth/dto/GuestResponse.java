package com.star_pick.starpick.domain.auth.dto;

import com.star_pick.starpick.domain.user.entity.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;

public record GuestResponse(
        Long userId,
        @Schema(description = "사용자 유형", example = "GUEST") AccountType accountType,
        String accessToken,
        String refreshToken,
        @Schema(description = "남은 레시피 저장 슬롯", example = "10") int remainingRecipeSlots,
        boolean onboardingRequired
) { }
