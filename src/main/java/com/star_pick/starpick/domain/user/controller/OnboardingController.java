package com.star_pick.starpick.domain.user.controller;

import com.star_pick.starpick.domain.user.dto.OnboardingResponse;
import com.star_pick.starpick.domain.user.service.OnboardingService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/onboarding")
@Tag(name = "온보딩", description = "계정별 온보딩 상태 및 완료 처리")
public class OnboardingController {
    private final OnboardingService onboarding;

    @GetMapping
    @Operation(summary = "내 온보딩 상태 조회", description = "앱 시작 및 자동 로그인 시 조회합니다. 유효한 access token이 필요합니다.")
    public ApiResponse<OnboardingResponse> status(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok("온보딩 상태를 조회했습니다.", onboarding.status(user.userId()));
    }

    @PostMapping("/complete")
    @Operation(summary = "내 온보딩 완료", description = "온보딩 마지막 단계에서 호출합니다. 요청 본문은 없으며 반복 호출해도 최초 완료 시각을 유지합니다.")
    public ApiResponse<OnboardingResponse> complete(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok("온보딩이 완료되었습니다.", onboarding.complete(user.userId()));
    }
}
