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
import com.star_pick.starpick.domain.auth.dto.GuestResponse;
import com.star_pick.starpick.domain.auth.dto.TokenRefreshRequest;
import com.star_pick.starpick.domain.auth.dto.TokenRefreshResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.HttpHeaders;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "게스트 생성, 소셜 로그인 및 인증 관련 API")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "게스트 생성", description = """
            인증 헤더와 요청 바디 없이 게스트 사용자, 기본 프로필, 별 10개와 인증 토큰을 생성합니다.
            개인정보나 소셜 인증, 약관 동의 완료를 요구하거나 자동 기록하지 않습니다.
            요청마다 새 게스트를 생성하므로 재실행/토큰 갱신 목적으로 호출하지 마세요.
            발급 토큰은 기존 API와 토큰 재발급 API에서 사용합니다. 기존 토큰 만료 정책을 따릅니다.
            회원가입 시 데이터 정리와 만료된 게스트 인증의 복구는 이 API의 범위가 아닙니다.
            """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "게스트 생성 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "생성 실패. 사용자·프로필·토큰 저장 롤백")
    })
    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GuestResponse> createGuest() {
        return ApiResponse.created("게스트 이용이 시작되었습니다.", authService.createGuest());
    }

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
            일반 가입은 Authorization 헤더 없이 호출합니다. 성공 시 새 회원의 토큰을 반환합니다.
            게스트 이용 후 신규 가입은 Authorization: Bearer {게스트 accessToken}을 함께 전달합니다.
            게스트 기록을 이전하지 않고 새 회원(별 10개, 온보딩 필요)을 생성합니다.
            새 회원 생성과 게스트 정리 대상 전환은 하나의 트랜잭션으로 처리됩니다.
            성공 즉시 기존 게스트의 일반 API 접근·토큰 갱신은 차단되고 데이터는 서버에서 순차 정리합니다.
            헤더를 전달했지만 인증에 실패한 경우 일반 가입으로 대체하지 않습니다.
            가입 실패 시 게스트 기록은 유지됩니다. 기존 회원 로그인은 이 전환을 수행하지 않습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원가입 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "필수 약관 미동의 또는 요청값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "가입 토큰 오류 또는 GUEST_TOKEN_INVALID: 게스트 토큰 오류·만료·계정 없음·정리 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "GUEST_ACCOUNT_REQUIRED: Authorization에 정식 회원 토큰을 전달함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 가입된 소셜 계정")
    })
    @PostMapping("/signup")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.ok("회원가입이 완료되었습니다.", authService.signup(request, authorization));
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
