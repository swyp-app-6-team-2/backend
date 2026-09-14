package com.star_pick.starpick.domain.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.star_pick.starpick.domain.notification.config.NotificationProperties;
import com.star_pick.starpick.domain.notification.service.NotificationDispatchService;
import com.star_pick.starpick.global.config.SchedulingConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class NotificationScheduleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(NotificationDispatchService.class, () -> mock(NotificationDispatchService.class))
            .withUserConfiguration(NotificationSchedule.class);

    @Test
    @DisplayName("FCM 프로젝트 id 가 있을 때만 알림 스케줄 Bean 이 등록된다")
    void registeredOnlyWithProjectId() {
        ApplicationContextRunner valid = runner.withBean(NotificationProperties.class, () -> properties("본문", "link"));

        valid.run(context -> assertThat(context).doesNotHaveBean(NotificationSchedule.class));
        valid.withPropertyValues("notification.fcm.project-id=starpick-mvp")
                .run(context -> assertThat(context).hasSingleBean(NotificationSchedule.class));
        valid.withPropertyValues("notification.fcm.project-id=starpick-mvp", "notification.external.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NotificationSchedule.class));
        // 분석용 Gemini 키만 있는 프로세스에서 알림이 돌면 안 된다.
        valid.withPropertyValues("ingestion.gemini.api-key=key")
                .run(context -> assertThat(context).doesNotHaveBean(NotificationSchedule.class));
        valid.withPropertyValues("ingestion.gemini.api-key=key", "notification.fcm.project-id=starpick-mvp")
                .run(context -> assertThat(context).hasSingleBean(NotificationSchedule.class));
    }

    @Test
    @DisplayName("전역 스케줄링과 함께 뜨면 FCM 프로젝트 id 가 있을 때만 매분 작업이 실제로 등록된다")
    void taskRegisteredWithGlobalScheduling() {
        ApplicationContextRunner scheduling = runner.withUserConfiguration(SchedulingConfig.class)
                .withBean(NotificationProperties.class, () -> properties("본문", "link"));

        scheduling.run(context -> assertThat(
                context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty());
        scheduling.withPropertyValues("notification.fcm.project-id=starpick-mvp")
                .run(context -> assertThat(
                        context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1));
    }

    @Test
    @DisplayName("Worker 를 맡는데 본문이나 딥링크가 비어 있으면 기동이 실패한다")
    void failsFastWithoutCopy() {
        runner.withBean(NotificationProperties.class, () -> properties("", "link"))
                .withPropertyValues("notification.fcm.project-id=starpick-mvp")
                .run(context -> assertThat(context).hasFailed());
        runner.withBean(NotificationProperties.class, () -> properties("본문", " "))
                .withPropertyValues("notification.fcm.project-id=starpick-mvp")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("발송 작업이 예외를 던져도 스케줄은 죽지 않는다")
    void runSwallowsException() {
        NotificationDispatchService dispatchService = mock(NotificationDispatchService.class);
        doThrow(new IllegalStateException("db down")).when(dispatchService).dispatch(any(Instant.class));
        NotificationSchedule schedule = new NotificationSchedule(dispatchService, properties("본문", "link"));

        assertThatCode(schedule::run).doesNotThrowAnyException();
    }

    private NotificationProperties properties(String mealBody, String deepLink) {
        return new NotificationProperties(
                new NotificationProperties.External(true),
                new NotificationProperties.Fcm("starpick-mvp"),
                new NotificationProperties.Message(mealBody),
                new NotificationProperties.Push(deepLink));
    }
}
