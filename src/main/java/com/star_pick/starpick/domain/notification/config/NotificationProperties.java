package com.star_pick.starpick.domain.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("notification")
public record NotificationProperties(External external, Fcm fcm, Message message, Push push) {

    /** false 면 실제 FCM Bean 을 만들지 않는다. 테스트 전용 스위치다. */
    public record External(boolean enabled) {
    }

    /** 비어 있으면 이 프로세스는 알림 Worker 를 맡지 않는다({@code NotificationWorkerCondition}). */
    public record Fcm(String projectId) {
    }

    public record Message(String mealBody) {
    }

    public record Push(String deepLink) {
    }
}
