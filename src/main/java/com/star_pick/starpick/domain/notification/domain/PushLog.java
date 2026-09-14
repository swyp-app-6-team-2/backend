package com.star_pick.starpick.domain.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 한 건. 기록은 {@code DueNotificationRecorder}, 변경은 {@code PushLogRepository} 의 컬럼 지정 UPDATE 로만 한다.
 * Entity 를 통째로 저장하면 발송 결과 반영이 먼저 기록된 {@code openedAt} 을 덮는다.
 */
@Entity
@Getter
@Table(name = "push_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long pushTokenId;

    @Column(nullable = false, updatable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PushStatus status;

    private Instant openedAt;
}
