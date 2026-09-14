package com.star_pick.starpick.domain.notification.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PushTokenUnregisterRequest(
        @NotBlank(message = "토큰은 비어 있을 수 없습니다.")
        @Size(max = 512, message = "토큰은 512자를 넘을 수 없습니다.")
        String token) {
}
