package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.ReelVideoResolver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토큰이 비면 보조 수집기 Bean 이 없다는 것을 여기서만 실측한다.
 * {@code IngestionWorkerConfigTest} 는 이 Config 를 로드하지 않고, {@code @SpringBootTest} 계열은
 * {@code ingestion.external.enabled: false} 가 먼저 잘라내서 토큰 조건까지 닿지 않는다.
 */
class ApifyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApifyConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

    @Test
    @DisplayName("토큰이 비어 있거나 없으면 보조 수집기 Bean 을 만들지 않는다")
    void noBeanWithoutToken() {
        runner.withBean(IngestionProperties.class, () -> properties(""))
                .run(context -> assertThat(context).doesNotHaveBean(ReelVideoResolver.class));
        runner.withBean(IngestionProperties.class, () -> properties(""))
                .withPropertyValues("ingestion.apify.token=")
                .run(context -> assertThat(context).doesNotHaveBean(ReelVideoResolver.class));
    }

    @Test
    @DisplayName("토큰이 채워지면 보조 수집기 Bean 을 만든다")
    void registersBeanWithToken() {
        runner.withBean(IngestionProperties.class, () -> properties("apify-token"))
                .withPropertyValues("ingestion.apify.token=apify-token")
                .run(context -> assertThat(context).hasSingleBean(ReelVideoResolver.class));
    }

    @Test
    @DisplayName("외부 호출을 끈 프로세스는 토큰이 있어도 만들지 않는다")
    void noBeanWhenExternalDisabled() {
        runner.withBean(IngestionProperties.class, () -> properties("apify-token"))
                .withPropertyValues("ingestion.apify.token=apify-token", "ingestion.external.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ReelVideoResolver.class));
    }

    private IngestionProperties properties(String apifyToken) {
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
                new IngestionProperties.YouTube("youtube-key", Duration.ofSeconds(10)),
                new IngestionProperties.Apify(apifyToken, "http://localhost", "apify~instagram-reel-scraper",
                        Duration.ofSeconds(20), Duration.ofSeconds(45)));
    }
}
