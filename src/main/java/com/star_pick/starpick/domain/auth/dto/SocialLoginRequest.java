package com.star_pick.starpick.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SocialLoginRequest(
        @NotBlank(message = "소셜 로그인 제공자는 필수입니다.") String provider,
        @NotBlank(message = "소셜 인증 토큰은 필수입니다.") String authToken,
        String nonce) {
}
