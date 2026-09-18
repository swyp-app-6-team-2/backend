package com.star_pick.starpick.domain.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Worker 를 맡는 프로세스의 필수 설정 검증. 이 Bean 이 기동을 막으므로 실패 경로를 반드시 테스트한다 —
 * 검증이 없으면 "키가 없으면 안 뜬다"가 dev 배포에서 처음 실행된다.
 */
class IngestionWorkerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IngestionWorkerConfig.class);

    @Test
    @DisplayName("Gemini 키가 있을 때만 Worker 설정 Bean 이 등록된다")
    void registeredOnlyWithGeminiKey() {
        runner.withBean(IngestionProperties.class, () -> properties("youtube-key"))
                .run(context -> assertThat(context).doesNotHaveBean(IngestionWorkerConfig.class));
        runner.withBean(IngestionProperties.class, () -> properties("youtube-key"))
                .withPropertyValues("ingestion.gemini.api-key=key")
                .run(context -> assertThat(context).hasSingleBean(IngestionWorkerConfig.class));
    }

    @Test
    @DisplayName("Worker 를 맡는데 YouTube 키가 없으면 기동하지 않는다")
    void failsFastWithoutYouTubeKey() {
        runner.withBean(IngestionProperties.class, () -> properties(""))
                .withPropertyValues("ingestion.gemini.api-key=key")
                .run(context -> assertThat(context).hasFailed());
        runner.withBean(IngestionProperties.class, () -> properties("  "))
                .withPropertyValues("ingestion.gemini.api-key=key")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Worker 를 맡지 않으면 YouTube 키가 없어도 기동한다")
    void startsWithoutYouTubeKeyWhenNotWorker() {
        runner.withBean(IngestionProperties.class, () -> properties(""))
                .run(context -> assertThat(context).hasNotFailed());
    }

    private IngestionProperties properties(String youTubeApiKey) {
        return new IngestionProperties(20,
                new IngestionProperties.Worker(2, Duration.ofSeconds(2)),
                new IngestionProperties.Job(Duration.ofSeconds(120), Duration.ofMinutes(3),
                        Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofDays(7)),
                new IngestionProperties.Retry(3, List.of(Duration.ofSeconds(2))),
                new IngestionProperties.Image(14680064),
                new IngestionProperties.External(true),
                new IngestionProperties.Gemini("key", "test", "http://localhost", Duration.ofSeconds(60), 0.2),
                new IngestionProperties.Instagram(Duration.ofSeconds(10), Duration.ofSeconds(30), 52428800,
                        Duration.ofSeconds(30)),
                new IngestionProperties.YouTube(youTubeApiKey, Duration.ofSeconds(10)),
                new IngestionProperties.Apify("", "http://localhost", "actor", Duration.ofSeconds(20),
                        Duration.ofSeconds(45)));
    }
}
