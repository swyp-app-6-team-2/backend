package com.star_pick.starpick.domain.user.controller;

import com.star_pick.starpick.domain.user.dto.*;
import com.star_pick.starpick.domain.user.service.UserService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me")
@Tag(name = "사용자", description = "사용자 온보딩 및 보유 재료 관리")
public class UserController {
    private final UserService users;

    @PostMapping("/ingredients")
    @Operation(summary = "재료 추가", description = "유효한 access token이 필요합니다. 활성 마스터 ID를 다건 등록하며 이미 보유한 재료는 무시합니다. 새로 추가된 재료만 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "신규 등록 목록 반환 또는 모두 보유한 경우 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청값 오류 또는 존재하지 않거나 비활성인 신규 재료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 또는 사용할 수 없는 사용자")
    })
    public ApiResponse<AddIngredientsResponse> add(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AddIngredientsRequest request) {
        var result = users.addIngredients(user.userId(), request.ingredientIds());
        return ApiResponse.ok(result.ingredients().isEmpty() ? "이미 모두 등록된 재료입니다." : "재료가 등록되었습니다.", result);
    }

    @GetMapping("/ingredients")
    @Operation(summary = "내 재료 목록 조회", description = "본인이 등록한 재료만 조회합니다. searchQuery는 재료명 부분 검색이며 빈 값은 전체 조회입니다. 카테고리 순서 및 이름 가나다순으로 반환하고 보유한 비활성 재료도 포함합니다.")
    public ApiResponse<UserIngredientsResponse> getIngredients(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String searchQuery) {
        var result = users.getIngredients(user.userId(), searchQuery);
        return ApiResponse.ok(result.ingredients().isEmpty() ? "조회된 재료가 없습니다." : "내 재료 목록을 조회했습니다.", result);
    }

    @GetMapping("/onboarding")
    @Operation(summary = "내 온보딩 상태 조회", description = "앱 시작 및 자동 로그인 시 조회합니다. 유효한 access token이 필요합니다.")
    public ApiResponse<OnboardingResponse> getOnboarding(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok("온보딩 상태를 조회했습니다.", users.getOnboarding(user.userId()));
    }

    @PostMapping("/onboarding/complete")
    @Operation(summary = "내 온보딩 완료", description = "온보딩 마지막 단계에서 호출합니다. 요청 본문은 없으며 반복 호출해도 최초 완료 시각을 유지합니다.")
    public ApiResponse<OnboardingResponse> completeOnboarding(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok("온보딩이 완료되었습니다.", users.completeOnboarding(user.userId()));
    }

    @GetMapping
    @Operation(summary = "내 정보 조회", description = "유효한 access token이 필요합니다. 닉네임, 프로필 이미지, 레시피 저장 슬롯 현황을 반환합니다.")
    public ApiResponse<MyInfoResponse> getMe(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok("내 정보 조회에 성공했습니다.", users.getMe(user.userId()));
    }

    @PatchMapping("/profile")
    @Operation(summary = "프로필 수정", description = "닉네임은 필수 1~6자입니다. profileImageKey는 생략 시 유지, null이면 삭제합니다. PROFILE_IMAGE 용도로 업로드한 objectKey를 사용합니다.")
    public ApiResponse<ProfileResponse> updateProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.ok("프로필이 수정되었습니다.", users.updateProfile(user.userId(), request));
    }
}
