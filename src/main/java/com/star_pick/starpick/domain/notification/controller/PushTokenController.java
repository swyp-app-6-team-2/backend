package com.star_pick.starpick.domain.notification.controller;

import com.star_pick.starpick.domain.notification.controller.request.PushTokenRegisterRequest;
import com.star_pick.starpick.domain.notification.controller.request.PushTokenUnregisterRequest;
import com.star_pick.starpick.domain.notification.service.PushTokenService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/push-tokens")
@Tag(name = "푸시 토큰", description = "기기 푸시 토큰 등록·해제 API")
public class PushTokenController {

    private final PushTokenService service;

    @PutMapping
    @Operation(summary = "푸시 토큰 등록", description = "다른 계정에 있던 토큰이면 현재 계정으로 옮기고 활성화합니다.")
    public ApiResponse<Void> register(@AuthenticationPrincipal AuthenticatedUser user,
                                      @Valid @RequestBody PushTokenRegisterRequest request) {
        service.register(user.userId(), request.token(), request.platform());
        return ApiResponse.ok("푸시 토큰이 저장되었습니다.", null);
    }

    @DeleteMapping
    @Operation(summary = "푸시 토큰 해제", description = "내 토큰이 아니거나 없어도 성공합니다.")
    public ApiResponse<Void> unregister(@AuthenticationPrincipal AuthenticatedUser user,
                                        @Valid @RequestBody PushTokenUnregisterRequest request) {
        service.unregister(user.userId(), request.token());
        return ApiResponse.ok("푸시 토큰이 해제되었습니다.", null);
    }
}
