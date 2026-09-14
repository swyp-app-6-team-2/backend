package com.star_pick.starpick.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocialLoginResponse (
        boolean requiresTermsAgreement,
        Long userId,
        String accessToken,
        String refreshToken,
        String signupToken,
        boolean onboardingRequired
) {
    public static SocialLoginResponse ofExistingUser(Long userId, String accessToken, String refreshToken, boolean onboardingRequired) {
        return new SocialLoginResponse(false, userId, accessToken, refreshToken, null, onboardingRequired);
    }

    public static SocialLoginResponse ofNewUser(String signupToken) {
        return new SocialLoginResponse(true, null, null, null, signupToken, true);
    }
}
