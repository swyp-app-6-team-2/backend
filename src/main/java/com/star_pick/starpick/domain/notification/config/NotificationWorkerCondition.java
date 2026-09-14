package com.star_pick.starpick.domain.notification.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 이 프로세스가 알림 발송을 맡을지 정한다. 켜고 끄는 스위치 없이 FCM 프로젝트 id 가 있는지로 정한다 —
 * 발송하려면 어차피 필요한 값이라 따로 잊을 설정이 없다({@code IngestionWorkerCondition} 과 같은 이유).
 */
public class NotificationWorkerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty("notification.external.enabled", Boolean.class, true)) {
            return false;
        }
        return StringUtils.hasText(environment.getProperty("notification.fcm.project-id"));
    }
}
