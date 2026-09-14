package com.star_pick.starpick.domain.notification.controller.response;

import com.star_pick.starpick.domain.notification.domain.NotificationSetting;
import java.util.List;

public record NotificationSettingResponse(boolean enabled, List<String> weekdays, List<TimeSlotResponse> timeSlots) {

    public static NotificationSettingResponse empty() {
        return new NotificationSettingResponse(false, List.of(), List.of());
    }

    public static NotificationSettingResponse from(NotificationSetting setting) {
        return new NotificationSettingResponse(
                setting.isEnabled(),
                setting.getWeekdays(),
                setting.getTimeSlots().stream().map(slot -> new TimeSlotResponse(slot.label(), slot.time())).toList());
    }
}
