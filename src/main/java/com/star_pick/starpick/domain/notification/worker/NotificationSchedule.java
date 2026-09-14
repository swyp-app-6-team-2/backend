package com.star_pick.starpick.domain.notification.worker;

import com.star_pick.starpick.domain.notification.config.NotificationProperties;
import com.star_pick.starpick.domain.notification.config.NotificationWorkerCondition;
import com.star_pick.starpick.domain.notification.service.NotificationDispatchService;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@Conditional(NotificationWorkerCondition.class)
public class NotificationSchedule {

    private final NotificationDispatchService dispatchService;

    public NotificationSchedule(NotificationDispatchService dispatchService, NotificationProperties properties) {
        // Worker 를 맡을 때만 필수다. 비어 있으면 본문 없는 푸시가 나가므로 기동을 막는다.
        if (!StringUtils.hasText(properties.message().mealBody()) || !StringUtils.hasText(properties.push().deepLink())) {
            throw new IllegalStateException(
                    "알림 Worker 를 맡으려면 notification.message.meal-body 와 notification.push.deep-link 가 필요합니다.");
        }
        this.dispatchService = dispatchService;
        log.info("알림 Worker 를 시작합니다. projectId={}", properties.fcm().projectId());
    }

    // 0초가 아니라 1초. 0초에 돌면 스케줄러가 몇 ms 일찍 깼을 때 이전 분으로 잘려 그 분을 놓친다.
    @Scheduled(cron = "1 * * * * *", zone = "Asia/Seoul")
    public void run() {
        try {
            dispatchService.dispatch(Instant.now());
        } catch (RuntimeException e) {
            log.error("식사 알림 발송 작업에 실패했습니다.", e);
        }
    }
}
