package com.star_pick.starpick.domain.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobExecutionService;
import com.star_pick.starpick.domain.ingestion.service.IngestionImageLoader;
import com.star_pick.starpick.domain.ingestion.service.PreemptedJob;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException;
import com.star_pick.starpick.domain.ingestion.service.RecipeDraftNormalizer;
import com.star_pick.starpick.domain.ingestion.service.TokenUsage;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.FakeRecipeAnalyzer;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IngestionJobProcessorTest {

    @Autowired
    private IngestionJobRepository repository;
    @Autowired
    private IngestionJobExecutionService executionService;
    @Autowired
    private IngestionJobProcessor processor;
    @Autowired
    private FakeObjectStorage storage;
    @Autowired
    private FakeRecipeAnalyzer analyzer;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IngestionProperties properties;
    @Autowired
    private IngestionImageLoader imageLoader;
    @Autowired
    private RecipeDraftNormalizer normalizer;
    @Autowired
    private IngredientService ingredientService;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        analyzer.clear();
        fixtures.seedUser(1L);
    }

    @Test
    @DisplayName("유효한 시도는 사진을 분석하고 정규화한 결과를 저장한다")
    void savesNormalizedResult() {
        PreemptedJob job = queuedAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));

        processor.process(job);

        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
        assertThat(saved.getResult().title()).isEqualTo("감자전");
        assertThat(saved.getResult().ingredients().getFirst().ingredientId())
                .isEqualTo(fixtures.ingredientId("VEG007"));
        assertThat(saved.getExpiresAt()).isNotNull();
        assertThat(analyzer.calls()).isOne();
    }

    @Test
    @DisplayName("무효가 된 시도는 외부 분석을 호출하지 않는다")
    void skipsInvalidAttempt() {
        PreemptedJob job = queuedAndPreempted();

        processor.process(new PreemptedJob(job.id(), job.attempt() + 1));

        assertThat(analyzer.calls()).isZero();
        assertThat(repository.findById(job.id()).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("재시도 가능한 실패는 두 번 재시도한 뒤 성공한다")
    void retriesTwiceThenSucceeds() {
        PreemptedJob job = queuedAndPreempted();
        analyzer.enqueue(new RecipeAnalysisException(
                RecipeAnalysisException.Kind.RETRYABLE, "temporary", null));
        analyzer.enqueue(new RecipeAnalysisException(
                RecipeAnalysisException.Kind.RETRYABLE, "temporary", null));
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(null, null)));

        processor.process(job);

        assertThat(analyzer.calls()).isEqualTo(3);
        assertThat(repository.findById(job.id()).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.RESULT_READY);
    }

    @Test
    @DisplayName("재시도 가능한 실패가 세 번 이어지면 PROCESSING_FAILED다")
    void failsAfterRetryBudgetIsExhausted() {
        PreemptedJob job = queuedAndPreempted();
        for (int call = 0; call < 3; call++) {
            analyzer.enqueue(new RecipeAnalysisException(
                    RecipeAnalysisException.Kind.RETRYABLE, "temporary", null));
        }

        processor.process(job);

        assertThat(analyzer.calls()).isEqualTo(3);
        assertFailure(job.id(), IngestionFailureCode.PROCESSING_FAILED);
    }

    @Test
    @DisplayName("복구 불가능한 분석 실패는 재시도하지 않는다")
    void doesNotRetryUnrecoverableFailure() {
        PreemptedJob job = queuedAndPreempted();
        analyzer.enqueue(new RecipeAnalysisException(
                RecipeAnalysisException.Kind.UNRECOVERABLE, "bad request", null));

        processor.process(job);

        assertThat(analyzer.calls()).isOne();
        assertFailure(job.id(), IngestionFailureCode.PROCESSING_FAILED);
    }

    @Test
    @DisplayName("레시피가 아니거나 차단된 입력은 CONTENT_NOT_RECOGNIZED다")
    void mapsUnrecognizedContent() {
        PreemptedJob notRecipe = queuedAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(Verdict.NOT_RECIPE, null, new TokenUsage(null, null)));
        processor.process(notRecipe);
        assertFailure(notRecipe.id(), IngestionFailureCode.CONTENT_NOT_RECOGNIZED);

        PreemptedJob blocked = queuedAndPreempted();
        analyzer.enqueue(new RecipeAnalysisException(
                RecipeAnalysisException.Kind.CONTENT_BLOCKED, "blocked", null));
        processor.process(blocked);
        assertFailure(blocked.id(), IngestionFailureCode.CONTENT_NOT_RECOGNIZED);

        PreemptedJob multiple = queuedAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(
                Verdict.MULTIPLE_RECIPES, null, new TokenUsage(null, null)));
        processor.process(multiple);
        assertFailure(multiple.id(), IngestionFailureCode.CONTENT_NOT_RECOGNIZED);

        PreemptedJob emptyDraft = queuedAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE,
                new RecipeDraft(null, null, null, null, List.of(), List.of()),
                new TokenUsage(null, null)));
        processor.process(emptyDraft);
        assertFailure(emptyDraft.id(), IngestionFailureCode.CONTENT_NOT_RECOGNIZED);
    }

    @Test
    @DisplayName("사진 준비 실패는 PROCESSING_FAILED다")
    void failsWhenImageIsMissing() {
        String key = "ingestion-inputs/1/missing.jpg";
        Long id = repository.save(IngestionJob.queueImage(1L, List.of(key))).getId();
        PreemptedJob job = executionService.preempt(1).getFirst();

        processor.process(job);

        assertFailure(id, IngestionFailureCode.PROCESSING_FAILED);
        assertThat(analyzer.calls()).isZero();
    }

    @Test
    @DisplayName("처리 deadline이 이미 지나면 사진은 읽어도 Analyzer는 호출하지 않는다")
    void skipsAnalyzerAfterDeadline() {
        PreemptedJob job = queuedAndPreempted();
        setStartedAt(job.id(), Instant.now().minus(properties.job().deadline()).minusSeconds(1));

        processor.process(job);

        assertThat(analyzer.calls()).isZero();
        assertFailure(job.id(), IngestionFailureCode.PROCESSING_FAILED);
    }

    @Test
    @DisplayName("사진 합계가 상한을 넘으면 읽기와 Analyzer 호출 없이 실패한다")
    void rejectsOversizedImagesBeforeReading() {
        String key = "ingestion-inputs/1/oversized.jpg";
        storage.putObject(key, new byte[Math.toIntExact(properties.image().maxTotalBytes() + 1)]);
        repository.save(IngestionJob.queueImage(1L, List.of(key)));
        PreemptedJob job = executionService.preempt(1).getFirst();

        processor.process(job);

        assertThat(storage.operations()).containsExactly("metadata");
        assertThat(analyzer.calls()).isZero();
        assertFailure(job.id(), IngestionFailureCode.PROCESSING_FAILED);
    }

    @Test
    @DisplayName("분석 중 시도가 바뀌면 늦게 끝난 결과를 폐기한다")
    void discardsLateResultAfterAttemptChanges() {
        PreemptedJob job = queuedAndPreempted();
        analyzer.enqueueAction(() -> jdbcTemplate.update("""
                update ingestion_job set status = 'QUEUED', attempt = attempt + 1 where id = ?
                """, job.id()), new AnalysisOutcome(
                Verdict.RECIPE, draft(), new TokenUsage(null, null)));

        processor.process(job);

        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.QUEUED);
        assertThat(saved.getAttempt()).isEqualTo(2);
        assertThat(saved.getResult()).isNull();
    }

    @Test
    @DisplayName("Analyzer timeout은 설정 상한과 남은 deadline 중 작은 값이다")
    void passesMinimumTimeoutToAnalyzer() {
        PreemptedJob configuredLimit = queuedAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(null, null)));
        processor.process(configuredLimit);
        assertThat(analyzer.lastTimeout()).isEqualTo(properties.gemini().analyzeTimeout());

        analyzer.clear();
        PreemptedJob remainingLimit = queuedAndPreempted();
        setStartedAt(remainingLimit.id(), Instant.now().minusSeconds(4));
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(null, null)));
        processorWithAnalyzeTimeout(Duration.ofSeconds(8)).process(remainingLimit);

        assertThat(analyzer.lastTimeout())
                .isLessThan(Duration.ofSeconds(8))
                .isGreaterThan(Duration.ofSeconds(5));
    }

    private PreemptedJob queuedAndPreempted() {
        String key = "ingestion-inputs/1/" + System.nanoTime() + ".jpg";
        storage.putObject(key, new byte[]{1});
        repository.save(IngestionJob.queueImage(1L, List.of(key)));
        return executionService.preempt(1).getFirst();
    }

    private RecipeDraft draft() {
        return new RecipeDraft(" 감자전 ", RecipeCategory.KOREAN, 30, 2,
                List.of(new RecipeDraft.Ingredient(null, "감자", "2개")),
                List.of(new RecipeDraft.Step(" 굽는다. ")));
    }

    private void setStartedAt(Long id, Instant startedAt) {
        jdbcTemplate.update("update ingestion_job set started_at = ? where id = ?",
                OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC), id);
    }

    private IngestionJobProcessor processorWithAnalyzeTimeout(Duration analyzeTimeout) {
        IngestionProperties custom = new IngestionProperties(
                properties.dailyLimit(), properties.worker(), properties.job(), properties.retry(),
                properties.image(), properties.external(),
                new IngestionProperties.Gemini(
                        properties.gemini().apiKey(), properties.gemini().model(),
                        properties.gemini().baseUrl(), analyzeTimeout));
        return new IngestionJobProcessor(
                executionService, imageLoader, analyzer, normalizer, ingredientService, custom);
    }

    private void assertFailure(Long id, IngestionFailureCode code) {
        IngestionJob saved = repository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(saved.getFailureCode()).isEqualTo(code);
    }
}
