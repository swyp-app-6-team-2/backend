package com.star_pick.starpick.domain.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.TokenUsage;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.FakeRecipeAnalyzer;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class IngestionWorkerTest {

    @Autowired
    private IngestionWorker worker;
    @Autowired
    private IngestionJobRepository repository;
    @Autowired
    private FakeObjectStorage storage;
    @Autowired
    private FakeRecipeAnalyzer analyzer;
    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        analyzer.clear();
        fixtures.seedUser(1L);
    }

    @Test
    @DisplayName("빈 슬롯만큼 처리하고 완료 후 다음 poll에서 남은 Job을 가져간다")
    void respectsConcurrencyAndReleasesSlots() {
        for (int index = 0; index < 3; index++) {
            String key = "ingestion-inputs/1/" + index + ".jpg";
            storage.putObject(key, new byte[]{1});
            repository.save(IngestionJob.queueImage(1L, List.of(key)));
            analyzer.enqueue(success());
        }

        worker.poll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(count(IngestionJobStatus.RESULT_READY)).isEqualTo(2));
        assertThat(count(IngestionJobStatus.QUEUED)).isOne();

        worker.poll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(count(IngestionJobStatus.RESULT_READY)).isEqualTo(3));
        assertThat(analyzer.calls()).isEqualTo(3);
    }

    @Test
    @DisplayName("대기 Job이 없으면 Analyzer를 호출하지 않는다")
    void doesNothingWithoutQueuedJobs() {
        worker.poll();
        assertThat(analyzer.calls()).isZero();
    }

    @Test
    @DisplayName("처리 중 예외가 발생해도 슬롯을 반환해 다음 Job을 처리한다")
    void releasesSlotAfterProcessingFailure() {
        saveQueued("failure.jpg");
        analyzer.enqueue(new IllegalStateException("analyzer failure"));

        worker.poll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(count(IngestionJobStatus.FAILED)).isOne());

        saveQueued("success.jpg");
        analyzer.enqueue(success());
        worker.poll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(count(IngestionJobStatus.RESULT_READY)).isOne());
        assertThat(analyzer.calls()).isEqualTo(2);
    }

    private void saveQueued(String filename) {
        String key = "ingestion-inputs/1/" + filename;
        storage.putObject(key, new byte[]{1});
        repository.save(IngestionJob.queueImage(1L, List.of(key)));
    }

    private long count(IngestionJobStatus status) {
        return repository.findAll().stream().filter(job -> job.getStatus() == status).count();
    }

    private AnalysisOutcome success() {
        return new AnalysisOutcome(Verdict.RECIPE,
                new RecipeDraft("감자전", RecipeCategory.KOREAN, null, 1,
                        List.of(new RecipeDraft.Ingredient(null, "감자", null)),
                        List.of(new RecipeDraft.Step("굽는다"))),
                new TokenUsage(null, null));
    }
}
