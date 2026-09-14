package com.star_pick.starpick.domain.notification.controller.request;

import com.star_pick.starpick.domain.notification.domain.PushPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 512자: 다중 바이트 문자여도 UNIQUE 인덱스 항목 한도(약 2.7KB) 안에 든다. FCM 토큰은 보통 150~300자다. */
public record PushTokenRegisterRequest(
        @NotBlank(message = "토큰은 비어 있을 수 없습니다.")
        @Size(max = 512, message = "토큰은 512자를 넘을 수 없습니다.")
        String token,

        @NotNull(message = "플랫폼은 필수입니다.")
        PushPlatform platform) {
}
