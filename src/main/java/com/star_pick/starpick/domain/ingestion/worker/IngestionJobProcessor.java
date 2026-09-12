package com.star_pick.starpick.domain.ingestion.worker;

import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.exception.IngestionInputException;
import com.star_pick.starpick.domain.ingestion.service.AnalysisInput;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.IngestionImageLoader;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobExecutionService;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobSnapshot;
import com.star_pick.starpick.domain.ingestion.service.PreemptedJob;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException.Kind;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalyzer;
import com.star_pick.starpick.domain.ingestion.service.RecipeDraftNormalizer;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IngestionJobProcessor {

    private static final Duration MIN_REMAINING_TO_CALL = Duration.ofSeconds(5);

    private final IngestionJobExecutionService executionService;
    private final IngestionImageLoader imageLoader;
    private final RecipeAnalyzer analyzer;
    private final RecipeDraftNormalizer normalizer;
    private final IngredientService ingredientService;
    private final IngestionProperties properties;

    public IngestionJobProcessor(IngestionJobExecutionService executionService,
                                 IngestionImageLoader imageLoader,
                                 RecipeAnalyzer analyzer,
                                 RecipeDraftNormalizer normalizer,
                                 IngredientService ingredientService,
                                 IngestionProperties properties) {
        this.executionService = executionService;
        this.imageLoader = imageLoader;
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
        try {
            var images = imageLoader.load(snapshot.inputImageKeys());
            AnalysisOutcome outcome = analyzeWithRetry(snapshot, new AnalysisInput(images));
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
            log.warn("입력을 준비할 수 없어 실패로 끝냅니다. ingestionJobId={}, reason={}",
                    snapshot.id(), e.getMessage());
            executionService.saveFailure(snapshot.id(), snapshot.attempt(),
                    IngestionFailureCode.PROCESSING_FAILED);
        } catch (RecipeAnalysisException e) {
            if (e.kind() == Kind.CONTENT_BLOCKED) {
                // 안전 필터가 사진을 거절한 것은 예상 가능한 결과다. ErrorCode 로 표현되는
                // 비즈니스 실패를 ERROR + Stack Trace 로 남기지 않는다(CLAUDE.md §9).
                log.warn("안전 차단으로 레시피를 인식하지 못했습니다. ingestionJobId={}, attempt={}, elapsedMs={}",
                        snapshot.id(), snapshot.attempt(), elapsedMs(startedNanos));
                executionService.saveFailure(snapshot.id(), snapshot.attempt(),
                        IngestionFailureCode.CONTENT_NOT_RECOGNIZED);
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

    private AnalysisOutcome analyzeWithRetry(IngestionJobSnapshot snapshot, AnalysisInput input) {
        Instant deadline = snapshot.startedAt().plus(properties.job().deadline());
        int maxAttempts = properties.retry().maxAttempts();
        for (int call = 1; ; call++) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.compareTo(MIN_REMAINING_TO_CALL) < 0) {
                throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "deadline 이 남지 않았다", null);
            }
            Duration timeout = min(properties.gemini().analyzeTimeout(), remaining);
            try {
                return analyzer.analyze(input, timeout);
            } catch (RecipeAnalysisException e) {
                if (e.kind() != Kind.RETRYABLE || call >= maxAttempts) {
                    throw e;
                }
                log.warn("재시도 가능한 분석 실패. ingestionJobId={}, call={}/{}",
                        snapshot.id(), call, maxAttempts);
                if (!sleepBeforeRetry(call, e.retryAfter(), deadline)) {
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
