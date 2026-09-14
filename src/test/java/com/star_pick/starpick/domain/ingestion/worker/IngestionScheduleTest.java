package com.star_pick.starpick.domain.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.star_pick.starpick.global.config.SchedulingConfig;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class IngestionScheduleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(IngestionWorker.class, () -> mock(IngestionWorker.class))
            .withBean(IngestionMaintenance.class, () -> mock(IngestionMaintenance.class))
            .withUserConfiguration(IngestionSchedule.class);

    @Test
    @DisplayName("Gemini 키가 있을 때만 Ingestion 스케줄 Bean 이 등록된다")
    void registeredOnlyWithGeminiKey() {
        runner.run(context -> assertThat(context).doesNotHaveBean(IngestionSchedule.class));
        runner.withPropertyValues("ingestion.gemini.api-key=key")
                .run(context -> assertThat(context).hasSingleBean(IngestionSchedule.class));
        runner.withPropertyValues("ingestion.gemini.api-key=key", "ingestion.external.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IngestionSchedule.class));
        // 알림용 FCM 설정만 있는 프로세스에서 Ingestion 이 돌면 안 된다.
        runner.withPropertyValues("notification.fcm.project-id=starpick-mvp")
                .run(context -> assertThat(context).doesNotHaveBean(IngestionSchedule.class));
        runner.withPropertyValues("ingestion.gemini.api-key=key", "notification.fcm.project-id=starpick-mvp")
                .run(context -> assertThat(context).hasSingleBean(IngestionSchedule.class));
    }

    @Test
    @DisplayName("전역 스케줄링과 함께 뜨면 Gemini 키가 있을 때만 주기 작업 5개가 실제로 등록된다")
    void tasksRegisteredWithGlobalScheduling() {
        // SchedulingConfig 가 빠지면 Bean 은 있어도 아무것도 돌지 않는다. 그 회귀를 여기서 잡는다.
        ApplicationContextRunner scheduling = runner.withUserConfiguration(SchedulingConfig.class)
                .withPropertyValues("ingestion.worker.poll-interval=1h");

        scheduling.run(context -> assertThat(
                context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty());
        scheduling.withPropertyValues("ingestion.gemini.api-key=key")
                .run(context -> assertThat(
                        context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(5));
    }

    @Test
    @DisplayName("@Scheduled 는 IngestionSchedule 에만 있다")
    void scheduledMovedToScheduleBean() {
        assertThat(scheduledCount(IngestionWorker.class)).isZero();
        assertThat(scheduledCount(IngestionMaintenance.class)).isZero();
        assertThat(scheduledCount(IngestionSchedule.class)).isEqualTo(5);
    }

    private long scheduledCount(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).filter(this::isScheduled).count();
    }

    private boolean isScheduled(Method method) {
        return method.isAnnotationPresent(Scheduled.class);
    }
}
