package com.star_pick.starpick.domain.ingestion.worker;

import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionSourceType;
import com.star_pick.starpick.domain.ingestion.domain.InstagramUrl;
import com.star_pick.starpick.domain.ingestion.domain.YouTubeUrl;
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
import com.star_pick.starpick.domain.ingestion.service.UploadedVideo;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import com.star_pick.starpick.domain.ingestion.service.VideoFileState;
import com.star_pick.starpick.domain.ingestion.service.YouTubeMetadataClient;
import com.star_pick.starpick.domain.upload.service.UploadService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

@Slf4j
@Component
public class IngestionJobProcessor {

    private static final Duration MIN_REMAINING_TO_CALL = Duration.ofSeconds(5);
    /** 올린 영상의 처리 상태를 확인하는 간격. 실측에서 ACTIVE 까지 6~7초였다. */
    private static final Duration VIDEO_STATE_POLL_INTERVAL = Duration.ofSeconds(1);
    /** 결과와 무관한 정리라 deadline 을 쓰지 않고 짧게 한 번만 시도한다. Gemini 도 48시간 뒤 스스로 지운다. */
    private static final Duration VIDEO_DELETE_TIMEOUT = Duration.ofSeconds(5);

    private final IngestionJobExecutionService executionService;
    private final IngestionImageLoader imageLoader;
    private final InstagramClient instagramClient;
    private final RecipeAnalyzer analyzer;
    private final RecipeDraftNormalizer normalizer;
    private final IngredientService ingredientService;
    private final IngestionProperties properties;
    private final UploadService uploadService;
    private final YouTubeMetadataClient youTubeMetadataClient;

    public IngestionJobProcessor(IngestionJobExecutionService executionService,
                                 IngestionImageLoader imageLoader,
                                 InstagramClient instagramClient,
                                 RecipeAnalyzer analyzer,
                                 RecipeDraftNormalizer normalizer,
                                 IngredientService ingredientService,
                                 IngestionProperties properties,
                                 UploadService uploadService,
                                 YouTubeMetadataClient youTubeMetadataClient) {
        this.executionService = executionService;
        this.imageLoader = imageLoader;
        this.instagramClient = instagramClient;
        this.analyzer = analyzer;
        this.normalizer = normalizer;
        this.ingredientService = ingredientService;
        this.properties = properties;
        this.uploadService = uploadService;
        this.youTubeMetadataClient = youTubeMetadataClient;
    }

    /** 분석 결과와, 성공하면 원본 대표 이미지로 복사할 주소. 대표 이미지는 Instagram 게시물의 첫 카드만 있다. */
    private record Analysis(AnalysisOutcome outcome, String thumbnailImageUrl) {
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
            Analysis analysis = switch (snapshot.sourceType()) {
                case IMAGE -> new Analysis(analyzeWithRetry(snapshot,
                        AnalysisInput.ofImages(imageLoader.load(snapshot.inputImageKeys(), deadline)), deadline), null);
                case YOUTUBE -> new Analysis(analyzeWithRetry(snapshot,
                        AnalysisInput.ofVideo(snapshot.inputUrl(), youTubeDescription(snapshot, deadline)),
                        deadline), null);
                case INSTAGRAM -> analyzeInstagram(snapshot, deadline);
            };
            AnalysisOutcome outcome = analysis.outcome();
            if (outcome.verdict() != Verdict.RECIPE) {
                IngestionFailureCode code = outcome.verdict() == Verdict.MULTIPLE_RECIPES
                        ? IngestionFailureCode.MULTIPLE_RECIPES : IngestionFailureCode.CONTENT_NOT_RECOGNIZED;
                finishFailed(snapshot, code, startedNanos, outcome);
                return;
            }
            var draft = normalizer.normalize(outcome.draft(), ingredientService.loadNameIndex());
            if (draft == null) {
                finishFailed(snapshot, IngestionFailureCode.CONTENT_NOT_RECOGNIZED, startedNanos, outcome);
                return;
            }
            String thumbnailKey = storeSourceThumbnail(snapshot, analysis.thumbnailImageUrl(), deadline);
            if (executionService.saveResult(snapshot.id(), snapshot.attempt(), draft, thumbnailKey)) {
                log.info("분석을 완료했습니다. ingestionJobId={}, sourceType={}, attempt={}, elapsedMs={}, tokens={}",
                        snapshot.id(), snapshot.sourceType(), snapshot.attempt(),
                        elapsedMs(startedNanos), outcome.usage());
            } else {
                uploadService.deleteSourceThumbnail(thumbnailKey);
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

    /**
     * 대표 이미지는 분석 카드와 따로 <b>게시물의 첫 카드</b>다. 게시자가 고른 얼굴이고, 카드 지정 링크({@code img_index})로
     * 레시피 한 장만 분석하게 하는 흐름을 대표 이미지가 방해하지 않는다. 첫 카드가 영상이면 그 썸네일이다.
     */
    private Analysis analyzeInstagram(IngestionJobSnapshot snapshot, Instant deadline) {
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
        String thumbnailImageUrl = post.media().getFirst().displayUrl();
        if (selection.videoUrl() != null) {
            return new Analysis(analyzeReel(snapshot, selection.videoUrl(), post.caption(), deadline), thumbnailImageUrl);
        }
        return new Analysis(analyzeWithRetry(snapshot,
                AnalysisInput.ofInstagramPost(downloadImages(snapshot, selection.imageUrls(), deadline), post.caption()),
                deadline), thumbnailImageUrl);
    }

    /**
     * 영상 설명란. 분석 결과와 무관한 best-effort 라 실패하면 null 이고 분석은 그대로 진행한다.
     *
     * <p>{@link #callWithRetry} 를 쓰지 않는다. 그 메서드는 실패를 분석 실패로 올리는데, 설명란 때문에
     * 이미 가능한 분석이 실패로 바뀌면 안 된다. 재시도도 하지 않는다.
     *
     * <p><b>남은 시간이 {@code fetchTimeout + MIN_REMAINING_TO_CALL} 이하면 아예 부르지 않는다.</b>
     * 이 호출은 분석보다 <i>앞</i>에 있어, 잔여만 보고 시작하면 설명란이 분석 예산을 먹을 수 있다.
     * 뒤에서 도는 {@link #storeSourceThumbnail} 과 다른 점이다.
     *
     * <p>로그에 응답 본문이나 설명란 내용을 남기지 않는다. 상태코드는 남긴다 — 키 무효와 쿼터 소진을
     * 구분해야 한다.
     */
    private String youTubeDescription(IngestionJobSnapshot snapshot, Instant deadline) {
        YouTubeUrl url = YouTubeUrl.parse(snapshot.inputUrl()).orElse(null);
        if (url == null) {
            // 저장된 URL 은 생성 때 정규화한 값이라 여기서 실패하면 데이터 드리프트 신호다.
            // best-effort 라 분석을 막지는 않지만 흔적은 남긴다.
            log.warn("저장된 YouTube 링크를 해석할 수 없어 설명란을 건너뜁니다. ingestionJobId={}", snapshot.id());
            return null;
        }
        Duration fetchTimeout = properties.youtube().fetchTimeout();
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.compareTo(fetchTimeout.plus(MIN_REMAINING_TO_CALL)) <= 0) {
            log.warn("deadline 이 모자라 영상 설명란을 건너뜁니다. ingestionJobId={}", snapshot.id());
            return null;
        }
        try {
            return youTubeMetadataClient.description(url.videoId(), fetchTimeout);
        } catch (RuntimeException e) {
            log.warn("영상 설명란을 받지 못했습니다. ingestionJobId={}, cause={}", snapshot.id(), cause(e));
            return null;
        }
    }

    /**
     * 실패 원인을 로그에 남길 짧은 문자열. <b>상태코드가 있으면 그것을 쓴다</b> — 키 무효(400)와
     * 쿼터 소진(403)을 구분해야 하고, 그 구분이 "기동은 됐는데 설명란만 빠지는" 상태를 관찰하는 유일한 수단이다.
     *
     * <p>{@code UnknownContentTypeException} 을 따로 보는 이유는 그것이
     * {@code RestClientResponseException} 이 <b>아니라</b> {@code RestClientException} 을 직접 상속하기 때문이다.
     * 프록시나 오류 페이지가 {@code 200 + text/html} 로 답하면 이 경로로 오는데, 묶어서 처리하면 상태코드를 잃는다.
     *
     * <p>본문과 URL 은 남기지 않는다. 예외 메시지에 응답 본문이 들어 있을 수 있다.
     */
    private static String cause(RuntimeException e) {
        if (e instanceof RestClientResponseException response) {
            return "status=" + response.getStatusCode().value();
        }
        if (e instanceof UnknownContentTypeException unknown) {
            return "status=" + unknown.getStatusCode().value();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * 원본 대표 이미지를 받아 저장소에 복사하고 Key 를 돌려준다. 분석 결과와 무관한 best-effort 라 실패하면 null 이다.
     *
     * <p>{@link #callWithRetry} 를 쓰지 않는다. 그 메서드는 deadline 이 모자라면 분석 실패 예외를 던져, 대표 이미지
     * 때문에 이미 성공한 분석이 실패로 바뀐다. 재시도도 하지 않는다.
     *
     * <p>로그에 예외 메시지를 남기지 않는다. HTTP 클라이언트 예외 메시지에는 서명된 CDN 주소가 들어갈 수 있다.
     */
    private String storeSourceThumbnail(IngestionJobSnapshot snapshot, String imageUrl, Instant deadline) {
        if (imageUrl == null) {
            return null;
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.compareTo(MIN_REMAINING_TO_CALL) < 0) {
            log.warn("deadline 이 남지 않아 원본 대표 이미지를 건너뜁니다. ingestionJobId={}", snapshot.id());
            return null;
        }
        try {
            InlineImage image = instagramClient.downloadImage(imageUrl, properties.image().maxTotalBytes(),
                    min(properties.instagram().mediaTimeout(), remaining));
            return uploadService.storeSourceThumbnail(snapshot.userId(), image.content(), image.mimeType());
        } catch (RuntimeException e) {
            log.warn("원본 대표 이미지를 저장하지 못했습니다. ingestionJobId={}, error={}",
                    snapshot.id(), e.getClass().getSimpleName());
            return null;
        }
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

    private AnalysisOutcome analyzeReel(IngestionJobSnapshot snapshot, String videoUrl, String caption, Instant deadline) {
        IngestionProperties.Instagram config = properties.instagram();
        Path file = null;
        UploadedVideo uploaded = null;
        try {
            file = Files.createTempFile("starpick-reel-", ".mp4");
            Path target = file;
            long size = callWithRetry(snapshot, deadline, config.mediaTimeout(), "instagram-video",
                    timeout -> instagramClient.downloadVideo(videoUrl, target, config.maxVideoBytes(), timeout));
            uploaded = uploadOnce(target, size, deadline);
            awaitActive(snapshot, uploaded, deadline);
            return analyzeWithRetry(snapshot, AnalysisInput.ofInstagramReel(uploaded.uri(), caption), deadline);
        } catch (IOException e) {
            throw new UncheckedIOException("영상 임시 파일을 만들 수 없다", e);
        } finally {
            deleteUploadedVideo(snapshot, uploaded);
            deleteTempFile(snapshot, file);
        }
    }

    /**
     * 업로드는 재시도하지 않는다. 본문이 서버에 도착했는데 응답만 늦으면 재시도가 새 파일을 만들고,
     * 첫 파일은 이름을 몰라 지울 수 없기 때문이다(Gemini 가 48시간 뒤 지운다).
     */
    private UploadedVideo uploadOnce(Path file, long size, Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.compareTo(MIN_REMAINING_TO_CALL) < 0) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "deadline 이 남지 않았다", null);
        }
        return analyzer.uploadVideo(file, size, min(properties.instagram().uploadTimeout(), remaining));
    }

    /** ACTIVE 가 될 때까지 deadline 안에서 확인한다. 한 번 확인할 때마다 5초 규칙을 거친다. */
    private void awaitActive(IngestionJobSnapshot snapshot, UploadedVideo video, Instant deadline) {
        while (true) {
            VideoFileState state = callWithRetry(snapshot, deadline, properties.instagram().fetchTimeout(),
                    "video-state", timeout -> analyzer.videoState(video, timeout));
            if (state == VideoFileState.ACTIVE) {
                return;
            }
            if (state == VideoFileState.FAILED) {
                throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "분석용 영상 처리에 실패했다", null);
            }
            try {
                Thread.sleep(VIDEO_STATE_POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "영상 준비를 기다리다 중단됐다", null);
            }
        }
    }

    private void deleteUploadedVideo(IngestionJobSnapshot snapshot, UploadedVideo video) {
        if (video == null) {
            return;
        }
        try {
            analyzer.deleteVideo(video, VIDEO_DELETE_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("분석용 영상을 지우지 못했습니다. ingestionJobId={}, reason={}", snapshot.id(), e.getMessage());
        }
    }

    private void deleteTempFile(IngestionJobSnapshot snapshot, Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("영상 임시 파일을 지우지 못했습니다. ingestionJobId={}", snapshot.id());
        }
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
