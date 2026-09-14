package com.star_pick.starpick.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public record SignupRequest(
        @NotBlank(message = "회원가입 인증 토큰은 필수입니다.") String signupToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "만 14세 이상 확인, true 필수") Boolean ageOver14Agreed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "이용약관 동의, true 필수") Boolean serviceTermsAgreed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "개인정보 동의, true 필수") Boolean privacyAgreed,
        @NotNull(message = "마케팅 정보 수신 동의 여부를 전달해주세요.") Boolean marketingAgreed,
        @NotNull(message = "서비스 알림 수신 동의 여부를 전달해주세요.") Boolean serviceAgreed
) { }
