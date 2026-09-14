package com.star_pick.starpick.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(
        @NotBlank(message = "리프레시 토큰은 필수입니다.") String refreshToken
) { }
