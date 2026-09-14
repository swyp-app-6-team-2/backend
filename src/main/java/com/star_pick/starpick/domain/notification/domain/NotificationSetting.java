package com.star_pick.starpick.domain.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 사용자당 하나. 생성은 {@code NotificationSettingRepository#insertIfAbsent} 가 한다. */
@Entity
@Getter
@Table(name = "notification_setting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean enabled;

    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] weekdays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<TimeSlot> timeSlots;

    /** 전체 교체. 요일은 월→일, 시간대는 시각 순으로 정렬해 저장한다(조회는 저장 순서를 그대로 쓴다). */
    public void replace(boolean enabled, List<DayOfWeek> weekdays, List<TimeSlot> timeSlots) {
        this.enabled = enabled;
        this.weekdays = weekdays.stream().sorted().map(DayOfWeek::name).toArray(String[]::new);
        this.timeSlots = timeSlots.stream().sorted(Comparator.comparing(TimeSlot::time)).toList();
    }

    public List<String> getWeekdays() {
        return List.of(weekdays);
    }
}
