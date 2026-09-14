package com.star_pick.starpick.domain.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

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
