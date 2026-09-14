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
import jakarta.validation.Valid;
import com.star_pick.starpick.domain.auth.dto.SignupRequest;
import com.star_pick.starpick.domain.auth.dto.SignupResponse;
import com.star_pick.starpick.domain.auth.service.SignupService;
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
    private final SignupService signupService;

    @Operation(summary = "회원가입", security = {}, description = """
            신규 소셜 사용자의 signupToken과 약관 동의를 받아 가입합니다.
            만 14세 이상, 이용약관, 개인정보 동의는 true 필수이며 선택 동의는 false로 가입할 수 있습니다.
            accessToken은 필요하지 않습니다. 성공 시 바로 로그인할 수 있는 토큰을 반환합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원가입 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "필수 약관 미동의 또는 요청값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "가입 토큰이 잘못되었거나 만료됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 가입된 소셜 계정")
    })
    @PostMapping("/signup")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok("회원가입이 완료되었습니다.", signupService.signup(request));
    }

    @Operation(
            summary = "소셜 로그인",
            description = """
                    provider: GOOGLE, KAKAO, NAVER, APPLE (대소문자 구분 없음)
                    authToken: 구글·애플은 ID token, 카카오·네이버는 access token입니다.
                    nonce: 애플 인증 요청에 nonce를 사용했다면 같은 값을 함께 전달합니다.
                    기존 회원은 accessToken/refreshToken, 신규 사용자는 signupToken을 반환합니다.
                    이메일은 선택 정보이며 없어도 로그인할 수 있습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "필수값 누락 또는 지원하지 않는 provider",
                    content = @Content),
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
    public ApiResponse<SocialLoginResponse> socialLogin(@Valid @RequestBody SocialLoginRequest request) {
        SocialLoginResponse response = socialLoginService.login(request);
        String message = response.requiresTermsAgreement()
                ? "약관 동의가 필요합니다."
                : "로그인에 성공했습니다.";
        return ApiResponse.ok(message, response);
    }
}
