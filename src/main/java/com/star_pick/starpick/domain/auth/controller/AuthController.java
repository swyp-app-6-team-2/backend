package com.star_pick.starpick.domain.auth.controller;

import com.star_pick.starpick.domain.auth.dto.SocialLoginRequest;
import com.star_pick.starpick.domain.auth.dto.SocialLoginResponse;
import com.star_pick.starpick.domain.auth.service.SocialLoginService;
import com.star_pick.starpick.global.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "소셜 로그인 및 인증 관련 API")
public class AuthController {

    private final SocialLoginService socialLoginService;

    @Operation(
            summary = "소셜 로그인",
            description = """
                    클라이언트가 소셜 Provider 인증을 마친 후 전달한 authToken으로 로그인/신규가입 여부를 판단합니다."
                    - "기존 회원이면 즉시 로그인 처리(accessToken/refreshToken 발급)하고,<br>"
                    - "신규 회원이면 signupToken을 발급하여 약관 동의 화면으로 유도합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "로그인 성공 또는 약관 동의 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "소셜 인증에 실패했습니다. (유효하지 않은 토큰)",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "소셜 인증 서버 오류",
                    content = @Content)
    })

    @PostMapping("/social-login")
    public ApiResponse<SocialLoginResponse> socialLogin(@RequestBody SocialLoginRequest request) {
        SocialLoginResponse response = socialLoginService.login(request);
        String message = response.requiresTermsAgreement()
                ? "약관 동의가 필요합니다."
                : "로그인에 성공했습니다.";
        return ApiResponse.ok(message, response);
    }
}
