package com.star_pick.starpick.domain.notification.controller;

import com.star_pick.starpick.domain.notification.service.NotificationOpenService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
@Tag(name = "알림", description = "푸시 알림 오픈 기록 API")
public class NotificationController {

    private final NotificationOpenService openService;

    @PostMapping("/{notificationId}/open")
    @Operation(summary = "알림 오픈 기록",
            description = "푸시 data 의 notificationId(문자열)를 숫자로 바꿔 호출합니다. 다시 불러도 첫 시각을 유지합니다.")
    public ApiResponse<Void> open(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long notificationId) {
        openService.open(user.userId(), notificationId);
        return ApiResponse.ok("알림 오픈이 기록되었습니다.", null);
    }
}
