package com.star_pick.starpick.domain.notification.controller.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

public record TimeSlotRequest(
        @NotBlank(message = "알림 제목은 비어 있을 수 없습니다.")
        @Size(max = 255, message = "알림 제목은 255자를 넘을 수 없습니다.")
        String label,

        // lenient 를 끄지 않으면 "24:00" 이 다음 날 00:00 으로 받아들여진다.
        @NotNull(message = "알림 시각은 필수입니다.")
        @JsonFormat(pattern = "HH:mm", lenient = OptBoolean.FALSE)
        LocalTime time) {
}
