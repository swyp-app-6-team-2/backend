package com.star_pick.starpick.domain.notification.domain;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * {@code notification_setting.time_slots} 의 요소.
 *
 * <p>{@code time} 을 {@code LocalTime} 이 아니라 {@code "HH:mm"} 문자열로 둔다. 발송 조회가 jsonb 값을
 * 문자열로 비교하는데, {@code LocalTime} 을 JSON 매퍼에 맡기면 {@code "12:00:00"} 으로 저장돼 전부 빗나간다.
 */
public record TimeSlot(String label, String time) {

    public static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static TimeSlot of(String label, LocalTime time) {
        return new TimeSlot(label, time.format(HH_MM));
    }
}
