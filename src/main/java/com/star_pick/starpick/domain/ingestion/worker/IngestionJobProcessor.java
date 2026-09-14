package com.star_pick.starpick.domain.ingestion.worker;

import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionSourceType;
import com.star_pick.starpick.domain.ingestion.domain.InstagramUrl;
import com.star_pick.starpick.domain.ingestion.exception.IngestionInputException;
import com.star_pick.starpick.domain.ingestion.service.AnalysisInput;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.IngestionImageLoader;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobExecutionService;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobSnapshot;
import com.star_pick.starpick.domain.ingestion.service.InlineImage;
import com.star_pick.starpick.domain.ingestion.service.InstagramClient;
import com.star_pick.starpick.domain.ingestion.service.InstagramFetchException;
import com.star_pick.starpick.domain.ingestion.service.InstagramPost;
import com.star_pick.starpick.domain.ingestion.service.InstagramSelection;
import com.star_pick.starpick.domain.ingestion.service.PreemptedJob;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException.Kind;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalyzer;
import com.star_pick.starpick.domain.ingestion.service.RecipeDraftNormalizer;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IngestionJobProcessor {

    private static final Duration MIN_REMAINING_TO_CALL = Duration.ofSeconds(5);

    private final IngestionJobExecutionService executionService;
    private final IngestionImageLoader imageLoader;
    private final InstagramClient instagramClient;
    private final RecipeAnalyzer analyzer;
    private final RecipeDraftNormalizer normalizer;
    private final IngredientService ingredientService;
    private final IngestionProperties properties;

    public IngestionJobProcessor(IngestionJobExecutionService executionService,
                                 IngestionImageLoader imageLoader,
                                 InstagramClient instagramClient,
                                 RecipeAnalyzer analyzer,
                                 RecipeDraftNormalizer normalizer,
                                 IngredientService ingredientService,
                                 IngestionProperties properties) {
        this.executionService = executionService;
        this.imageLoader = imageLoader;
        this.instagramClient = instagramClient;
        this.analyzer = analyzer;
        this.normalizer = normalizer;
        this.ingredientService = ingredientService;
        this.properties = properties;
    }

    public void process(PreemptedJob job) {
        IngestionJobSnapshot snapshot = executionService.loadForProcessing(job.id(), job.attempt()).orElse(null);
        if (snapshot == null) {
            log.warn("유효하지 않은 시도라 처리를 건너뜁니다. ingestionJobId={}, attempt={}",
                    job.id(), job.attempt());
            return;
        }

        long startedNanos = System.nanoTime();
        // Job 하나에 허용한 예산. 입력 준비와 분석 호출이 이 하나를 나눠 쓴다.
        Instant deadline = snapshot.startedAt().plus(properties.job().deadline());
        try {
            AnalysisOutcome outcome = switch (snapshot.sourceType()) {
                case IMAGE -> analyzeWithRetry(snapshot,
                        AnalysisInput.ofImages(imageLoader.load(snapshot.inputImageKeys(), deadline)), deadline);
                case YOUTUBE -> analyzeWithRetry(snapshot, AnalysisInput.ofVideo(snapshot.inputUrl()), deadline);
                case INSTAGRAM -> analyzeInstagram(snapshot, deadline);
            };
            if (outcome.verdict() != Verdict.RECIPE) {
                finishFailed(snapshot, IngestionFailureCode.CONTENT_NOT_RECOGNIZED, startedNanos, outcome);
                return;
            }
            var draft = normalizer.normalize(outcome.draft(), ingredientService.loadNameIndex());
            if (draft == null) {
                finishFailed(snapshot, IngestionFailureCode.CONTENT_NOT_RECOGNIZED, startedNanos, outcome);
                return;
            }
            if (executionService.saveResult(snapshot.id(), snapshot.attempt(), draft)) {
                log.info("분석을 완료했습니다. ingestionJobId={}, sourceType={}, attempt={}, elapsedMs={}, tokens={}",
                        snapshot.id(), snapshot.sourceType(), snapshot.attempt(),
                        elapsedMs(startedNanos), outcome.usage());
            }
        } catch (IngestionInputException e) {
            log.warn("입력을 준비할 수 없어 실패로 끝냅니다. ingestionJobId={}, sourceType={}, failureCode={}, reason={}",
                    snapshot.id(), snapshot.sourceType(), e.failureCode(), e.getMessage());
            executionService.saveFailure(snapshot.id(), snapshot.attempt(), e.failureCode());
        } catch (InstagramFetchException e) {
            if (e.kind() == InstagramFetchException.Kind.RETRYABLE) {
                log.error("Instagram 수집이 재시도 끝에 실패했습니다. ingestionJobId={}, attempt={}, elapsedMs={}",
                        snapshot.id(), snapshot.attempt(), elapsedMs(startedNanos), e);
                executionService.saveFailure(snapshot.id(), snapshot.attempt(), IngestionFailureCode.PROCESSING_FAILED);
            } else {
                IngestionFailureCode code = e.kind() == InstagramFetchException.Kind.TOO_LARGE
                        ? IngestionFailureCode.PROCESSING_FAILED : IngestionFailureCode.SOURCE_UNAVAILABLE;
                // 삭제·비공개 게시물, embed 구조 변경, 차단(429)이 여기로 온다. 몰리면 구조 변경이나 차단부터 의심한다.
                log.warn("Instagram 원본을 쓸 수 없어 실패로 끝냅니다. ingestionJobId={}, attempt={}, kind={}, failureCode={}, reason={}, elapsedMs={}",
                        snapshot.id(), snapshot.attempt(), e.kind(), code, e.getMessage(), elapsedMs(startedNanos));
                executionService.saveFailure(snapshot.id(), snapshot.attempt(), code);
            }
        } catch (RecipeAnalysisException e) {
            if (e.kind() == Kind.CONTENT_BLOCKED) {
                // 안전 필터가 입력을 거절한 것은 예상 가능한 결과다. ErrorCode 로 표현되는
                // 비즈니스 실패를 ERROR + Stack Trace 로 남기지 않는다(CLAUDE.md §9).
                log.warn("안전 차단으로 레시피를 인식하지 못했습니다. ingestionJobId={}, attempt={}, elapsedMs={}",
                        snapshot.id(), snapshot.attempt(), elapsedMs(startedNanos));
                executionService.saveFailure(snapshot.id(), snapshot.attempt(),
                        IngestionFailureCode.CONTENT_NOT_RECOGNIZED);
            } else if (e.kind() == Kind.INPUT_REJECTED && snapshot.sourceType() == IngestionSourceType.YOUTUBE) {
                // 없는 영상·볼 수 없는 영상이 대부분이라 ERROR 로 남기지 않는다(CLAUDE.md §9). 우리 요청 형식
                // 오류도 같은 400 으로 올 수 있어, 이 WARN 이 몰리면 요청 형식부터 의심한다.
                log.warn("Gemini 가 영상 입력을 거절했습니다. ingestionJobId={}, sourceType={}, attempt={}, failureCode={}, elapsedMs={}",
                        snapshot.id(), snapshot.sourceType(), snapshot.attempt(),
                        IngestionFailureCode.SOURCE_UNAVAILABLE, elapsedMs(startedNanos));
                executionService.saveFailure(snapshot.id(), snapshot.attempt(),
                        IngestionFailureCode.SOURCE_UNAVAILABLE);
            } else {
                log.error("분석이 최종 실패했습니다. ingestionJobId={}, kind={}, attempt={}, elapsedMs={}",
                        snapshot.id(), e.kind(), snapshot.attempt(), elapsedMs(startedNanos), e);
                executionService.saveFailure(snapshot.id(), snapshot.attempt(),
                        IngestionFailureCode.PROCESSING_FAILED);
            }
        } catch (RuntimeException e) {
            log.error("예상하지 못한 오류로 분석이 실패했습니다. ingestionJobId={}", snapshot.id(), e);
            executionService.saveFailure(snapshot.id(), snapshot.attempt(),
                    IngestionFailureCode.PROCESSING_FAILED);
        }
    }

    private AnalysisOutcome analyzeInstagram(IngestionJobSnapshot snapshot, Instant deadline) {
        InstagramUrl url = InstagramUrl.parse(snapshot.inputUrl())
                .orElseThrow(() -> new IngestionInputException("저장된 Instagram 링크를 해석할 수 없다"));
        InstagramPost post = callWithRetry(snapshot, deadline, properties.instagram().fetchTimeout(), "instagram-embed",
                timeout -> instagramClient.fetchPost(url.shortcode(), url.reel(), timeout));
        InstagramSelection selection = InstagramSelection.of(post, url.imgIndex());
        String preview = selection.previewImageUrl();
        if (preview != null && preview.length() <= IngestionJob.PREVIEW_IMAGE_URL_MAX_LENGTH) {
            executionService.savePreview(snapshot.id(), snapshot.attempt(), preview);
        }
        if (selection.failureCode() != null) {
            throw new IngestionInputException(selection.failureCode(), "분석할 카드가 없다");
        }
        if (selection.videoUrl() != null) {
            throw new IngestionInputException("Instagram 영상은 아직 처리하지 않는다");
        }
        return analyzeWithRetry(snapshot,
                AnalysisInput.ofInstagramPost(downloadImages(snapshot, selection.imageUrls(), deadline), post.caption()),
                deadline);
    }

    /** 이미지 카드를 순서대로 받는다. 합계 상한은 사진 입력과 같은 값이고, 남은 몫을 다음 카드의 상한으로 넘긴다. */
    private List<InlineImage> downloadImages(IngestionJobSnapshot snapshot, List<String> urls, Instant deadline) {
        long remainingBytes = properties.image().maxTotalBytes();
        List<InlineImage> images = new ArrayList<>(urls.size());
        for (String url : urls) {
            long limit = remainingBytes;
            InlineImage image = callWithRetry(snapshot, deadline, properties.instagram().mediaTimeout(),
                    "instagram-image", timeout -> instagramClient.downloadImage(url, limit, timeout));
            remainingBytes -= image.content().length;
            images.add(image);
        }
        return images;
    }

    private AnalysisOutcome analyzeWithRetry(IngestionJobSnapshot snapshot, AnalysisInput input, Instant deadline) {
        return callWithRetry(snapshot, deadline, properties.gemini().analyzeTimeout(), "analyze",
                timeout -> analyzer.analyze(input, timeout));
    }

    /**
     * 외부 호출 하나를 Job 의 재시도·deadline 규칙으로 감싼다. timeout 은 {@code min(단계 상한, 남은 시간)}.
     * 재시도 가능으로 분류된 실패만 다시 부른다.
     */
    private <T> T callWithRetry(IngestionJobSnapshot snapshot, Instant deadline, Duration stageTimeout,
                                String stage, Function<Duration, T> call) {
        int maxAttempts = properties.retry().maxAttempts();
        for (int attemptOfCall = 1; ; attemptOfCall++) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.compareTo(MIN_REMAINING_TO_CALL) < 0) {
                throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "deadline 이 남지 않았다", null);
            }
            try {
                return call.apply(min(stageTimeout, remaining));
            } catch (RecipeAnalysisException | InstagramFetchException e) {
                boolean retryable = e instanceof RecipeAnalysisException analysis
                        ? analysis.kind() == Kind.RETRYABLE
                        : ((InstagramFetchException) e).kind() == InstagramFetchException.Kind.RETRYABLE;
                if (!retryable || attemptOfCall >= maxAttempts) {
                    throw e;
                }
                log.warn("재시도 가능한 외부 호출 실패. ingestionJobId={}, stage={}, call={}/{}",
                        snapshot.id(), stage, attemptOfCall, maxAttempts);
                Duration retryAfter = e instanceof RecipeAnalysisException analysis ? analysis.retryAfter() : null;
                if (!sleepBeforeRetry(attemptOfCall, retryAfter, deadline)) {
                    throw e;
                }
            }
        }
    }

    private boolean sleepBeforeRetry(int call, Duration retryAfter, Instant deadline) {
        var backoffs = properties.retry().backoffs();
        Duration base = retryAfter != null ? retryAfter
                : backoffs.get(Math.min(call - 1, backoffs.size() - 1));
        long jitterBound = Math.max(1, base.toMillis() / 4 + 1);
        Duration delay = base.plusMillis(ThreadLocalRandom.current().nextLong(jitterBound));
        if (Duration.between(Instant.now().plus(delay), deadline)
                .compareTo(MIN_REMAINING_TO_CALL) < 0) {
            return false;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 분석은 됐지만 결과를 레시피로 쓸 수 없을 때.
     *
     * <p>성공 경로와 같은 식별자를 남긴다(spec §3.4). 특히 <b>토큰 사용량을 빠뜨리지 않는다</b> —
     * 인식에 실패한 호출도 비용은 이미 나갔으므로, 여기서 빠지면 비용 집계에 구멍이 생긴다.
     */
    private void finishFailed(IngestionJobSnapshot snapshot, IngestionFailureCode code,
                              long startedNanos, AnalysisOutcome outcome) {
        log.warn("분석 결과를 레시피로 쓸 수 없습니다. ingestionJobId={}, sourceType={}, attempt={},"
                        + " verdict={}, failureCode={}, elapsedMs={}, tokens={}",
                snapshot.id(), snapshot.sourceType(), snapshot.attempt(),
                outcome.verdict(), code, elapsedMs(startedNanos), outcome.usage());
        executionService.saveFailure(snapshot.id(), snapshot.attempt(), code);
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
