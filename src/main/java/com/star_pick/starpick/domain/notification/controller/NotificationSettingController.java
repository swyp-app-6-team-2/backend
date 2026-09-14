package com.star_pick.starpick.domain.notification.controller;

import com.star_pick.starpick.domain.notification.controller.request.NotificationSettingRequest;
import com.star_pick.starpick.domain.notification.controller.response.NotificationSettingResponse;
import com.star_pick.starpick.domain.notification.service.NotificationSettingService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notification-settings")
@Tag(name = "알림 설정", description = "식사 알림 수신 여부·요일·시간대 API")
public class NotificationSettingController {

    private final NotificationSettingService service;

    @GetMapping
    @Operation(summary = "알림 설정 조회", description = "설정이 없으면 꺼진 기본값을 줍니다.")
    public ApiResponse<NotificationSettingResponse> get(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok("알림 설정을 조회했습니다.", service.get(user.userId()));
    }

    @PutMapping
    @Operation(summary = "알림 설정 저장",
            description = "세 필드를 모두 보내 전체 교체합니다. 켜져 있어도 요일·시간대가 비어 있을 수 있습니다.")
    public ApiResponse<Void> save(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody NotificationSettingRequest request) {
        service.save(user.userId(), request);
        return ApiResponse.ok("알림 설정이 저장되었습니다.", null);
    }
}
