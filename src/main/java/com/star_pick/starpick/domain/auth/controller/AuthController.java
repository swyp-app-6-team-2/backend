package com.star_pick.starpick.domain.auth.controller;

import com.star_pick.starpick.domain.auth.dto.SocialLoginRequest;
import com.star_pick.starpick.domain.auth.dto.SocialLoginResponse;
import com.star_pick.starpick.domain.auth.service.SocialLoginService;
import com.star_pick.starpick.global.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final SocialLoginService socialLoginService;

    @PostMapping("/social-login")
    public ApiResponse<SocialLoginResponse> socialLogin(@RequestBody SocialLoginRequest request) {
        SocialLoginResponse response = socialLoginService.login(request);
        String message = response.requiresTermsAgreement()
                ? "약관 동의가 필요합니다."
                : "로그인에 성공했습니다.";
        return ApiResponse.ok(message, response);
    }
}
