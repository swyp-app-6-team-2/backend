package com.star_pick.starpick.domain.auth.controller;

import com.star_pick.starpick.domain.auth.dto.SocialLoginRequest;
import com.star_pick.starpick.domain.auth.service.AuthService;
import com.star_pick.starpick.domain.auth.dto.SocialLoginResponse;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import com.star_pick.starpick.domain.auth.dto.SignupRequest;
import com.star_pick.starpick.domain.auth.dto.SignupResponse;
import com.star_pick.starpick.domain.auth.dto.TokenRefreshRequest;
import com.star_pick.starpick.domain.auth.dto.TokenRefreshResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "소셜 로그인 및 인증 관련 API")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "토큰 재발급", description = """
            유효한 refreshToken으로 accessToken과 refreshToken을 새로 발급합니다.
            Authorization 헤더는 필요하지 않습니다. 재발급에 성공하면 이전 refreshToken은 사용할 수 없습니다.
            """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "토큰 누락 또는 요청 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "만료·폐기·재사용·잘못된 refresh token")
    })
    @PostMapping("/token/refresh")
    public ApiResponse<TokenRefreshResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        var tokens = authService.refresh(request.refreshToken());
        return ApiResponse.ok("토큰이 재발급되었습니다.", new TokenRefreshResponse(tokens.accessToken(), tokens.refreshToken()));
    }

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
        return ApiResponse.ok("회원가입이 완료되었습니다.", authService.signup(request));
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
        SocialLoginResponse response = authService.login(request);
        String message = response.requiresTermsAgreement()
                ? "약관 동의가 필요합니다."
                : "로그인에 성공했습니다.";
        return ApiResponse.ok(message, response);
    }

    @Operation(
            summary = "로그아웃",
            description = """
                        사용자에게 저장된 refreshToken을 무효화합니다.
                        accessToken은 만료까지 유효하므로 클라이언트에서도 두 토큰을 삭제해야 합니다.
                        Authorization 헤더에 유효한 accessToken이 필요합니다.
                        """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "로그아웃 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증이 필요합니다.",
                    content = @Content)
    })
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
        authService.revoke(principal.userId());
        return ApiResponse.ok("로그아웃되었습니다.", null);
    }
}
