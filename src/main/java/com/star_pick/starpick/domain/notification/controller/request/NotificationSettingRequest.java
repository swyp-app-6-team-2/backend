package com.star_pick.starpick.domain.notification.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 전체 교체. 세 필드 모두 필수이고, 켜져 있어도 빈 배열을 허용한다. */
public record NotificationSettingRequest(
        @NotNull(message = "알림 수신 여부는 필수입니다.")
        Boolean enabled,

        @NotNull(message = "요일 목록은 필수입니다.")
        List<@NotNull(message = "요일은 비어 있을 수 없습니다.") DayOfWeek> weekdays,

        @NotNull(message = "시간대 목록은 필수입니다.")
        List<@NotNull(message = "시간대는 비어 있을 수 없습니다.") @Valid TimeSlotRequest> timeSlots) {

    // null 이면 true 를 돌려 @NotNull 에 맡긴다. 여기서 NPE 가 나면 400 이 아니라 500 이 된다.
    @Schema(hidden = true)
    @AssertTrue(message = "요일이 중복되었습니다.")
    public boolean isWeekdaysUnique() {
        return weekdays == null || new HashSet<>(weekdays).size() == weekdays.size();
    }

    @Schema(hidden = true)
    @AssertTrue(message = "같은 시각이 중복되었습니다.")
    public boolean isTimesUnique() {
        if (timeSlots == null) {
            return true;
        }
        List<LocalTime> times = timeSlots.stream()
                .filter(Objects::nonNull).map(TimeSlotRequest::time).filter(Objects::nonNull).toList();
        return new HashSet<>(times).size() == times.size();
    }
}
