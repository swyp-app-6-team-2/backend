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
    private final com.star_pick.starpick.domain.user.service.UserWithdrawalService withdrawals;

    @DeleteMapping
    @Operation(summary = "회원 탈퇴", description = "외부 소셜 연결을 해제한 뒤 계정과 사용자 데이터를 삭제합니다. 네이버 로그인 사용자는 socialAccessToken이 필수이며, 연결 해제에 실패하면 계정을 삭제하지 않습니다. 중간 데이터 삭제 실패는 서버에서 자동 복구합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원 탈퇴 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "네이버 access token 누락 또는 요청값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 또는 사용할 수 없는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "네이버 계정 연결 해제 실패")
    })
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody(required = false) UserWithdrawalRequest request) {
        withdrawals.withdraw(user.userId(), request == null ? null : request.socialAccessToken());
        return ApiResponse.ok("회원 탈퇴가 완료되었습니다.", null);
    }

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
    @Operation(summary = "내 재료 목록 조회", description = "본인이 등록한 재료만 조회합니다. searchQuery는 재료명 부분 검색이며 빈 값은 전체 조회입니다. 카테고리 순서 및 이름 가나다순으로 반환하고 보유한 비활성 재료도 포함합니다. 커스텀 재료는 뒤에 이름순·ID순으로 배치합니다.")
    public ApiResponse<UserIngredientsResponse> getIngredients(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String searchQuery) {
        var result = users.getIngredients(user.userId(), searchQuery);
        return ApiResponse.ok(result.ingredients().isEmpty() ? "조회된 재료가 없습니다." : "내 재료 목록을 조회했습니다.", result);
    }

    @PostMapping("/ingredients/custom")
    @Operation(summary = "커스텀 재료 추가", description = "유효한 access token이 필요합니다. 재료명을 앞뒤 공백 제거 후 1~50자로 저장하며 마스터·기존 커스텀 재료와 이름이 같아도 새 항목으로 등록합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록된 커스텀 재료 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "재료명 누락·공백·길이 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 또는 사용할 수 없는 사용자")
    })
    public ApiResponse<UserIngredientResponse> addCustomIngredient(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CustomIngredientCreateRequest request) {
        return ApiResponse.ok("재료가 등록되었습니다.", users.addCustomIngredient(user.userId(), request.name()));
    }

    @DeleteMapping("/ingredients")
    @Operation(summary = "보유 재료 삭제", description = "마스터·커스텀 재료를 선택 또는 전체 삭제합니다. 이미 삭제된 대상은 무시하며 레시피 자체는 삭제하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 결과 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "삭제 범위 또는 대상 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 또는 사용할 수 없는 사용자")
    })
    public ApiResponse<DeleteIngredientsResponse> deleteIngredients(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DeleteIngredientsRequest request) {
        var result = users.deleteIngredients(user.userId(), request);
        return ApiResponse.ok(result.deletedCount() == 0 ? "삭제된 재료가 없습니다." : "재료가 삭제되었습니다.", result);
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
