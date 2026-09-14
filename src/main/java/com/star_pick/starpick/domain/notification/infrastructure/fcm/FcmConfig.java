package com.star_pick.starpick.domain.notification.infrastructure.fcm;

import com.star_pick.starpick.domain.notification.config.NotificationProperties;
import com.star_pick.starpick.domain.notification.service.PushGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 테스트가 아닌 모든 프로세스에 Bean 이 생긴다(API 만 맡는 프로세스 포함). Firebase 초기화는 첫 발송 때라
 * 발송하지 않는 프로세스에는 비용도 자격증명 요구도 없다. 테스트는 {@code notification.external.enabled=false} 로 끄고
 * {@code FakePushGateway} 를 쓴다.
 */
@Configuration
@ConditionalOnProperty(prefix = "notification.external", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FcmConfig {

    @Bean
    PushGateway pushGateway(NotificationProperties properties) {
        return new FcmPushGateway(properties.fcm().projectId());
    }
}
