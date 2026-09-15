package com.star_pick.starpick.domain.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.domain.InstagramUrl;
import com.star_pick.starpick.domain.ingestion.domain.YouTubeUrl;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.ingestion.service.AnalysisInput;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.InstagramFetchException;
import com.star_pick.starpick.domain.ingestion.service.InstagramMedia;
import com.star_pick.starpick.domain.ingestion.service.InstagramPost;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobExecutionService;
import com.star_pick.starpick.domain.ingestion.service.IngestionImageLoader;
import com.star_pick.starpick.domain.ingestion.service.PreemptedJob;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException;
import com.star_pick.starpick.domain.ingestion.service.RecipeDraftNormalizer;
import com.star_pick.starpick.domain.ingestion.service.TokenUsage;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import com.star_pick.starpick.domain.ingestion.service.VideoFileState;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.support.FakeInstagramClient;
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

    @Autowired
    private FakeInstagramClient instagram;
    @Autowired
    private UploadService uploadService;

    private static final String YOUTUBE_URL = "https://www.youtube.com/watch?v=kjG6h_LTklo";
    private static final String CDN = "https://scontent-ssn1-1.cdninstagram.com/";
    private static final String POST_URL = "https://www.instagram.com/p/DKI9fBzy5FB/";
    private static final String REEL_URL = "https://www.instagram.com/reel/DcdllvBmOgm/";

    @BeforeEach
    void setUp() {
        fixtures.reset();
        analyzer.clear();
        instagram.clear();
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
    @DisplayName("레시피가 아니거나 차단된 입력은 CONTENT_NOT_RECOGNIZED, 여러 레시피는 MULTIPLE_RECIPES 다")
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
        assertFailure(multiple.id(), IngestionFailureCode.MULTIPLE_RECIPES);

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

    @Test
    @DisplayName("YouTube Job 은 사진을 읽지 않고 링크를 분석에 넘긴다")
    void analyzesYouTubeLinkWithoutReadingStorage() {
        PreemptedJob job = queuedYouTubeAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        int operationsBefore = storage.operations().size();

        processor.process(job);

        assertThat(analyzer.lastInput().videoUrl()).isEqualTo(YOUTUBE_URL);
        assertThat(analyzer.lastInput().images()).isEmpty();
        assertThat(storage.operations()).hasSize(operationsBefore);
        assertThat(repository.findById(job.id()).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.RESULT_READY);
    }

    @Test
    @DisplayName("입력 거절은 재시도하지 않고 YouTube 는 SOURCE_UNAVAILABLE, 사진은 PROCESSING_FAILED 다")
    void mapsRejectedInputBySourceType() {
        PreemptedJob youtube = queuedYouTubeAndPreempted();
        analyzer.enqueue(new RecipeAnalysisException(
                RecipeAnalysisException.Kind.INPUT_REJECTED, "rejected", null));
        processor.process(youtube);
        assertThat(analyzer.calls()).isOne();
        assertFailure(youtube.id(), IngestionFailureCode.SOURCE_UNAVAILABLE);

        PreemptedJob image = queuedAndPreempted();
        analyzer.enqueue(new RecipeAnalysisException(
                RecipeAnalysisException.Kind.INPUT_REJECTED, "rejected", null));
        processor.process(image);
        assertThat(analyzer.calls()).isEqualTo(2);
        assertFailure(image.id(), IngestionFailureCode.PROCESSING_FAILED);
    }

    @Test
    @DisplayName("img_index 없는 carousel 은 이미지 카드 전부를 caption 과 함께 분석하고 첫 이미지를 미리보기로 저장한다")
    void analyzesAllImageCardsOfCarousel() {
        instagram.enqueuePost(new InstagramPost("콩나물밥 만드는 법", List.of(
                image(CDN + "1.jpg"), video(CDN + "2.jpg", CDN + "2.mp4"), image(CDN + "3.jpg"))));
        instagram.putMedia(CDN + "1.jpg", new byte[]{1});
        instagram.putMedia(CDN + "3.jpg", new byte[]{3});
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL);

        processor.process(job);

        AnalysisInput input = analyzer.lastInput();
        assertThat(input.source()).isEqualTo(AnalysisInput.Source.INSTAGRAM_POST);
        assertThat(input.images()).extracting(image -> image.content()[0]).containsExactly((byte) 1, (byte) 3);
        assertThat(input.caption()).isEqualTo("콩나물밥 만드는 법");
        assertThat(instagram.lastFetch()).isEqualTo("DKI9fBzy5FB post");
        // 분석 카드 둘은 합계 상한을 나눠 쓰고, 마지막은 성공 뒤 대표 이미지로 받는 첫 카드라 상한이 따로다.
        assertThat(instagram.imageLimits()).containsExactly(
                properties.image().maxTotalBytes(), properties.image().maxTotalBytes() - 1,
                properties.image().maxTotalBytes());
        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
        assertThat(saved.getPreviewImageUrl()).isEqualTo(CDN + "1.jpg");
    }

    @Test
    @DisplayName("img_index 가 가리키는 이미지 카드 하나만 분석한다")
    void analyzesIndexedCard() {
        instagram.enqueuePost(new InstagramPost(null, List.of(image(CDN + "1.jpg"), image(CDN + "2.jpg"))));
        instagram.putMedia(CDN + "2.jpg", new byte[]{2});
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL + "?img_index=2");

        processor.process(job);

        assertThat(analyzer.lastInput().images()).extracting(image -> image.content()[0]).containsExactly((byte) 2);
        assertThat(repository.findById(job.id()).orElseThrow().getPreviewImageUrl()).isEqualTo(CDN + "2.jpg");
    }

    @Test
    @DisplayName("분석할 카드가 없으면 Gemini 를 부르지 않는다 — 영상 카드는 CONTENT_NOT_RECOGNIZED, 카드 수 초과는 SOURCE_UNAVAILABLE")
    void failsWithoutAnalyzableCard() {
        InstagramPost carousel = new InstagramPost(null, List.of(image(CDN + "1.jpg"), video(CDN + "2.jpg", CDN + "2.mp4")));

        instagram.enqueuePost(carousel);
        PreemptedJob videoCard = queuedInstagramAndPreempted(POST_URL + "?img_index=2");
        processor.process(videoCard);
        assertFailure(videoCard.id(), IngestionFailureCode.CONTENT_NOT_RECOGNIZED);
        assertThat(repository.findById(videoCard.id()).orElseThrow().getPreviewImageUrl()).isEqualTo(CDN + "2.jpg");

        instagram.enqueuePost(carousel);
        PreemptedJob outOfRange = queuedInstagramAndPreempted(POST_URL + "?img_index=9");
        processor.process(outOfRange);
        assertFailure(outOfRange.id(), IngestionFailureCode.SOURCE_UNAVAILABLE);
        assertThat(repository.findById(outOfRange.id()).orElseThrow().getPreviewImageUrl()).isNull();

        assertThat(analyzer.calls()).isZero();
    }

    @Test
    @DisplayName("원본을 쓸 수 없으면 SOURCE_UNAVAILABLE, 이미지 상한 초과는 PROCESSING_FAILED, 둘 다 재시도하지 않는다")
    void mapsInstagramFetchFailures() {
        instagram.enqueuePostFailure(new InstagramFetchException(InstagramFetchException.Kind.UNAVAILABLE, "없음"));
        PreemptedJob unavailable = queuedInstagramAndPreempted(POST_URL);
        processor.process(unavailable);
        assertFailure(unavailable.id(), IngestionFailureCode.SOURCE_UNAVAILABLE);
        assertThat(instagram.fetchCalls()).isOne();

        instagram.enqueuePost(new InstagramPost(null, List.of(image(CDN + "big.jpg"))));
        instagram.failMedia(CDN + "big.jpg", new InstagramFetchException(InstagramFetchException.Kind.TOO_LARGE, "큼"));
        PreemptedJob tooLarge = queuedInstagramAndPreempted(POST_URL);
        processor.process(tooLarge);
        assertFailure(tooLarge.id(), IngestionFailureCode.PROCESSING_FAILED);

        assertThat(analyzer.calls()).isZero();
    }

    @Test
    @DisplayName("embed 수집의 재시도 가능한 실패는 분석과 같은 재시도 규칙을 따른다")
    void retriesInstagramFetch() {
        InstagramFetchException retryable = new InstagramFetchException(InstagramFetchException.Kind.RETRYABLE, "timeout");
        instagram.enqueuePostFailure(retryable);
        instagram.enqueuePostFailure(retryable);
        instagram.enqueuePost(new InstagramPost(null, List.of(image(CDN + "1.jpg"))));
        instagram.putMedia(CDN + "1.jpg", new byte[]{1});
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL);

        processor.process(job);

        assertThat(instagram.fetchCalls()).isEqualTo(3);
        assertThat(repository.findById(job.id()).orElseThrow().getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
    }

    @Test
    @DisplayName("embed 수집의 재시도 가능한 실패가 세 번 이어지면 PROCESSING_FAILED 다")
    void failsAfterInstagramRetriesAreExhausted() {
        for (int i = 0; i < 3; i++) {
            instagram.enqueuePostFailure(new InstagramFetchException(InstagramFetchException.Kind.RETRYABLE, "timeout"));
        }
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL);

        processor.process(job);

        assertThat(instagram.fetchCalls()).isEqualTo(3);
        assertFailure(job.id(), IngestionFailureCode.PROCESSING_FAILED);
        assertThat(analyzer.calls()).isZero();
    }

    @Test
    @DisplayName("미리보기는 유효한 시도에서만 저장된다")
    void savesPreviewOnlyForCurrentAttempt() {
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL);

        executionService.savePreview(job.id(), job.attempt() + 1, CDN + "stale.jpg");
        assertThat(repository.findById(job.id()).orElseThrow().getPreviewImageUrl()).isNull();

        executionService.savePreview(job.id(), job.attempt(), CDN + "1.jpg");
        assertThat(repository.findById(job.id()).orElseThrow().getPreviewImageUrl()).isEqualTo(CDN + "1.jpg");
    }

    @Test
    @DisplayName("Reel 은 임시 파일로 받아 올리고 ACTIVE 가 되면 분석한 뒤 올린 파일과 임시 파일을 지운다")
    void analyzesReelThroughUploadedFile() {
        instagram.enqueuePost(new InstagramPost("막김치", List.of(video(CDN + "thumb.jpg", CDN + "reel.mp4"))));
        instagram.putMedia(CDN + "reel.mp4", new byte[]{9, 9, 9});
        analyzer.enqueueVideoState(VideoFileState.PROCESSING);
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(REEL_URL);

        processor.process(job);

        AnalysisInput input = analyzer.lastInput();
        assertThat(input.source()).isEqualTo(AnalysisInput.Source.INSTAGRAM_REEL);
        assertThat(input.videoUrl()).isEqualTo(FakeRecipeAnalyzer.UPLOADED.uri());
        assertThat(input.caption()).isEqualTo("막김치");
        assertThat(instagram.lastFetch()).isEqualTo("DcdllvBmOgm reel");
        assertThat(analyzer.lastUploadedBytes()).containsExactly(9, 9, 9);
        assertThat(analyzer.deletedVideos()).isOne();
        assertThat(analyzer.lastUploadedFile()).doesNotExist();
        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
        assertThat(saved.getPreviewImageUrl()).isEqualTo(CDN + "thumb.jpg");
    }

    @Test
    @DisplayName("Instagram 분석이 성공하면 게시물 첫 카드를 원본 대표 이미지로 저장소에 복사하고 Key 를 결과와 함께 저장한다")
    void storesFirstCardAsSourceThumbnail() {
        instagram.enqueuePost(new InstagramPost(null, List.of(image(CDN + "1.jpg"), image(CDN + "2.jpg"))));
        instagram.putMedia(CDN + "1.jpg", new byte[]{1});
        instagram.putMedia(CDN + "2.jpg", new byte[]{2});
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL);

        processor.process(job);

        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
        assertThat(saved.getSourceThumbnailKey()).startsWith("source-thumbnails/1/");
        assertThat(storage.read(saved.getSourceThumbnailKey())).containsExactly(1);
    }

    @Test
    @DisplayName("카드 지정 링크도 대표 이미지는 분석한 카드가 아니라 첫 카드다")
    void storesFirstCardEvenForIndexedLink() {
        instagram.enqueuePost(new InstagramPost(null, List.of(image(CDN + "1.jpg"), image(CDN + "2.jpg"))));
        instagram.putMedia(CDN + "1.jpg", new byte[]{1});
        instagram.putMedia(CDN + "2.jpg", new byte[]{2});
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL + "?img_index=2");

        processor.process(job);

        assertThat(analyzer.lastInput().images()).extracting(image -> image.content()[0]).containsExactly((byte) 2);
        assertThat(instagram.imageDownloads()).containsExactly(CDN + "2.jpg", CDN + "1.jpg");
        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(storage.read(saved.getSourceThumbnailKey())).containsExactly(1);
    }

    @Test
    @DisplayName("Reel 의 대표 이미지는 영상 썸네일이다")
    void storesReelThumbnailAsSourceThumbnail() {
        instagram.enqueuePost(new InstagramPost(null, List.of(video(CDN + "thumb.jpg", CDN + "reel.mp4"))));
        instagram.putMedia(CDN + "reel.mp4", new byte[]{9});
        instagram.putMedia(CDN + "thumb.jpg", new byte[]{7});
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(REEL_URL);

        processor.process(job);

        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(storage.read(saved.getSourceThumbnailKey())).containsExactly(7);
    }

    @Test
    @DisplayName("대표 이미지를 받거나 저장하지 못해도 분석은 성공하고 Key 만 비어 있다")
    void keepsResultWhenSourceThumbnailFails() {
        InstagramPost carousel = new InstagramPost(null, List.of(image(CDN + "1.jpg"), image(CDN + "2.jpg")));
        instagram.putMedia(CDN + "2.jpg", new byte[]{2});

        instagram.failMedia(CDN + "1.jpg", new InstagramFetchException(InstagramFetchException.Kind.RETRYABLE, "down"));
        instagram.enqueuePost(carousel);
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob downloadFails = queuedInstagramAndPreempted(POST_URL + "?img_index=2");
        processor.process(downloadFails);

        instagram.putMedia(CDN + "1.jpg", new byte[]{1});
        storage.failWrites();
        instagram.enqueuePost(carousel);
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob writeFails = queuedInstagramAndPreempted(POST_URL + "?img_index=2");
        processor.process(writeFails);

        for (PreemptedJob job : List.of(downloadFails, writeFails)) {
            IngestionJob saved = repository.findById(job.id()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
            assertThat(saved.getSourceThumbnailKey()).isNull();
        }
        // 대표 이미지 다운로드는 재시도하지 않는다. 분석 카드 1회 + 첫 카드 1회.
        assertThat(instagram.imageDownloads()).containsExactly(
                CDN + "2.jpg", CDN + "1.jpg", CDN + "2.jpg", CDN + "1.jpg");
    }

    @Test
    @DisplayName("레시피로 쓸 수 없는 판정이면 대표 이미지를 저장하지 않는다")
    void doesNotStoreSourceThumbnailWhenNotRecipe() {
        instagram.enqueuePost(new InstagramPost(null, List.of(image(CDN + "1.jpg"))));
        instagram.putMedia(CDN + "1.jpg", new byte[]{1});
        analyzer.enqueue(new AnalysisOutcome(Verdict.MULTIPLE_RECIPES, null, new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL);

        processor.process(job);

        assertFailure(job.id(), IngestionFailureCode.MULTIPLE_RECIPES);
        assertThat(storage.operations()).doesNotContain("write");
    }

    @Test
    @DisplayName("사진·YouTube 분석은 대표 이미지를 저장하지 않는다")
    void doesNotStoreSourceThumbnailForImageOrYouTube() {
        PreemptedJob imageJob = queuedAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        processor.process(imageJob);

        PreemptedJob youTubeJob = queuedYouTubeAndPreempted();
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        processor.process(youTubeJob);

        for (PreemptedJob job : List.of(imageJob, youTubeJob)) {
            IngestionJob saved = repository.findById(job.id()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
            assertThat(saved.getSourceThumbnailKey()).isNull();
        }
        assertThat(storage.operations()).doesNotContain("write");
    }

    @Test
    @DisplayName("분석 뒤 deadline 이 남지 않으면 대표 이미지만 건너뛰고 분석은 성공한다")
    void skipsSourceThumbnailWhenDeadlineIsShort() {
        instagram.enqueuePost(new InstagramPost(null, List.of(image(CDN + "1.jpg"))));
        instagram.putMedia(CDN + "1.jpg", new byte[]{1});
        // 테스트 deadline 10초 중 4초가 지난 것으로 둔다. 분석 호출은 남은 6초로 5초 규칙을 통과하고,
        // 분석이 2초 걸린 뒤의 대표 이미지 단계에서는 남은 시간이 5초 미만이다.
        analyzer.enqueueAction(() -> sleep(Duration.ofSeconds(2)),
                new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL);
        setStartedAt(job.id(), Instant.now().minusSeconds(4));

        processor.process(job);

        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
        assertThat(saved.getSourceThumbnailKey()).isNull();
        assertThat(instagram.imageDownloads()).containsExactly(CDN + "1.jpg");
        assertThat(storage.operations()).doesNotContain("write");
    }

    @Test
    @DisplayName("첫 카드 주소가 없으면 받지 않고 Key 가 비어 있다")
    void skipsSourceThumbnailWithoutFirstCardUrl() {
        instagram.enqueuePost(new InstagramPost(null, List.of(image(null), image(CDN + "2.jpg"))));
        instagram.putMedia(CDN + "2.jpg", new byte[]{2});
        analyzer.enqueue(new AnalysisOutcome(Verdict.RECIPE, draft(), new TokenUsage(10, 20)));
        PreemptedJob job = queuedInstagramAndPreempted(POST_URL + "?img_index=2");

        processor.process(job);

        IngestionJob saved = repository.findById(job.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
        assertThat(saved.getSourceThumbnailKey()).isNull();
        assertThat(instagram.imageDownloads()).containsExactly(CDN + "2.jpg");
    }

    @Test
    @DisplayName("파일 처리가 FAILED 거나 deadline 까지 ACTIVE 가 되지 않으면 분석 없이 PROCESSING_FAILED 이고, 올린 파일은 지운다")
    void failsWhenUploadedVideoIsNotUsable() {
        instagram.enqueuePost(new InstagramPost(null, List.of(video(CDN + "thumb.jpg", CDN + "reel.mp4"))));
        instagram.putMedia(CDN + "reel.mp4", new byte[]{9});
        analyzer.enqueueVideoState(VideoFileState.FAILED);
        PreemptedJob failed = queuedInstagramAndPreempted(REEL_URL);
        processor.process(failed);
        assertFailure(failed.id(), IngestionFailureCode.PROCESSING_FAILED);

        instagram.enqueuePost(new InstagramPost(null, List.of(video(CDN + "thumb.jpg", CDN + "reel.mp4"))));
        for (int i = 0; i < 20; i++) {
            analyzer.enqueueVideoState(VideoFileState.PROCESSING);
        }
        PreemptedJob neverActive = queuedInstagramAndPreempted(REEL_URL);
        // 테스트 deadline 10초 중 3초가 지난 것으로 둔다. 남은 약 7초로 업로드까지 통과하고, 상태 확인이 1~2회 뒤 5초 규칙에 걸린다.
        setStartedAt(neverActive.id(), Instant.now().minusSeconds(3));
        processor.process(neverActive);
        assertFailure(neverActive.id(), IngestionFailureCode.PROCESSING_FAILED);
        assertThat(analyzer.uploads()).isEqualTo(2);

        assertThat(analyzer.calls()).isZero();
        assertThat(analyzer.deletedVideos()).isEqualTo(2);
        assertThat(analyzer.lastUploadedFile()).doesNotExist();
    }

    @Test
    @DisplayName("영상이 상한을 넘으면 올리지 않고 PROCESSING_FAILED 이며 임시 파일을 지운다")
    void rejectsOversizedReelBeforeUpload() {
        instagram.enqueuePost(new InstagramPost(null, List.of(video(CDN + "thumb.jpg", CDN + "big.mp4"))));
        instagram.failMedia(CDN + "big.mp4", new InstagramFetchException(InstagramFetchException.Kind.TOO_LARGE, "큼"));
        PreemptedJob job = queuedInstagramAndPreempted(REEL_URL);

        processor.process(job);

        assertFailure(job.id(), IngestionFailureCode.PROCESSING_FAILED);
        assertThat(analyzer.uploads()).isZero();
        assertThat(analyzer.deletedVideos()).isZero();
        assertThat(instagram.lastVideoTarget()).doesNotExist();
    }

    private PreemptedJob queuedInstagramAndPreempted(String url) {
        repository.save(IngestionJob.queueInstagram(1L, InstagramUrl.parse(url).orElseThrow()));
        return executionService.preempt(1).getFirst();
    }

    private static InstagramMedia image(String url) {
        return new InstagramMedia(false, url, null);
    }

    private static InstagramMedia video(String thumbnailUrl, String videoUrl) {
        return new InstagramMedia(true, thumbnailUrl, videoUrl);
    }

    private PreemptedJob queuedYouTubeAndPreempted() {
        repository.save(IngestionJob.queueYouTube(1L, YouTubeUrl.parse(YOUTUBE_URL).orElseThrow()));
        return executionService.preempt(1).getFirst();
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

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
                        properties.gemini().baseUrl(), analyzeTimeout, properties.gemini().videoFps()),
                properties.instagram());
        return new IngestionJobProcessor(
                executionService, imageLoader, instagram, analyzer, normalizer, ingredientService, custom,
                uploadService);
    }

    private void assertFailure(Long id, IngestionFailureCode code) {
        IngestionJob saved = repository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(saved.getFailureCode()).isEqualTo(code);
    }
}
