package com.star_pick.starpick.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.exception.IngestionInputException;
import com.star_pick.starpick.support.FakeObjectStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestionImageLoaderTest {

    private FakeObjectStorage storage;
    private IngestionImageLoader loader;

    @BeforeEach
    void setUp() {
        storage = new FakeObjectStorage();
        loader = new IngestionImageLoader(storage, properties(5));
    }

    @Test
    @DisplayName("모든 메타데이터를 먼저 확인한 뒤 Key 순서와 MIME을 보존해 읽는다")
    void loadsAfterCheckingAllSizes() {
        storage.putObject("ingestion-inputs/1/a.jpg", new byte[]{1, 2});
        storage.putObject("ingestion-inputs/1/b.png", new byte[]{3, 4, 5}, "image/png");

        List<InlineImage> result = loader.load(List.of(
                "ingestion-inputs/1/a.jpg", "ingestion-inputs/1/b.png"), later());

        assertThat(result).extracting(InlineImage::mimeType)
                .containsExactly("image/jpeg", "image/png");
        assertThat(result.get(0).content()).containsExactly(1, 2);
        assertThat(result.get(1).content()).containsExactly(3, 4, 5);
        assertThat(storage.operations()).containsExactly("metadata", "metadata", "read", "read");
    }

    @Test
    @DisplayName("합계 상한을 넘으면 바이트를 하나도 읽지 않는다")
    void rejectsOversizedInputBeforeReading() {
        storage.putObject("ingestion-inputs/1/a.jpg", new byte[]{1, 2, 3});
        storage.putObject("ingestion-inputs/1/b.webp", new byte[]{4, 5, 6}, "image/webp");

        assertThatThrownBy(() -> loader.load(List.of(
                "ingestion-inputs/1/a.jpg", "ingestion-inputs/1/b.webp"), later()))
                .isInstanceOf(IngestionInputException.class);
        assertThat(storage.operations()).containsExactly("metadata", "metadata");
    }

    @Test
    @DisplayName("없는 객체와 지원하지 않는 형식을 거절한다")
    void rejectsMissingObjectAndUnsupportedType() {
        assertThatThrownBy(() -> loader.load(List.of("ingestion-inputs/1/missing.jpg"), later()))
                .isInstanceOf(IngestionInputException.class);

        storage.clear();
        storage.putObject("ingestion-inputs/1/a.gif", new byte[]{1}, "image/gif");
        assertThatThrownBy(() -> loader.load(List.of("ingestion-inputs/1/a.gif"), later()))
                .isInstanceOf(IngestionInputException.class);
    }

    @Test
    @DisplayName("남은 시간이 없으면 저장소를 부르지 않고 끝낸다")
    void stopsWhenDeadlinePassed() {
        storage.putObject("ingestion-inputs/1/a.jpg", new byte[]{1});

        assertThatThrownBy(() -> loader.load(List.of("ingestion-inputs/1/a.jpg"), Instant.now()))
                .isInstanceOf(IngestionInputException.class);
        assertThat(storage.operations()).isEmpty();
    }

    /** deadline 이 넉넉한 정상 상황. */
    private Instant later() {
        return Instant.now().plusSeconds(60);
    }

    private IngestionProperties properties(long maxBytes) {
        return new IngestionProperties(20,
                new IngestionProperties.Worker(2, Duration.ofSeconds(2)),
                new IngestionProperties.Job(Duration.ofSeconds(10), Duration.ofMinutes(3),
                        Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofDays(7)),
                new IngestionProperties.Retry(3, List.of(Duration.ofMillis(10))),
                new IngestionProperties.Image(maxBytes),
                new IngestionProperties.External(false),
                new IngestionProperties.Gemini("", "test", "http://localhost", Duration.ofSeconds(5), 0.2),
                new IngestionProperties.Instagram(Duration.ofSeconds(10), Duration.ofSeconds(30), 52428800,
                        Duration.ofSeconds(30)),
                new IngestionProperties.YouTube("test-key", Duration.ofSeconds(10)));
    }
}
