package com.star_pick.starpick.domain.ingestion.infrastructure.gemini;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException.Kind;
import com.star_pick.starpick.domain.ingestion.service.TokenUsage;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.ingestion.service.AnalysisInput;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.InlineImage;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalyzer;
import com.star_pick.starpick.domain.ingestion.service.UploadedVideo;
import com.star_pick.starpick.domain.ingestion.service.VideoFileState;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiRecipeAnalyzer implements RecipeAnalyzer {

    private static final List<String> BLOCKED_FINISH_REASONS = List.of(
            "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY");
    private static final String VIDEO_MP4 = "video/mp4";
    private static final Duration UPLOAD_START_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient.Builder restClientBuilder;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final IngestionProperties.Gemini config;
    private final JsonNode responseJsonSchema;

    GeminiRecipeAnalyzer(RestClient.Builder restClientBuilder, HttpClient httpClient,
                         JsonMapper jsonMapper, IngestionProperties.Gemini config) {
        this.restClientBuilder = restClientBuilder;
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
        this.config = config;
        try {
            this.responseJsonSchema = jsonMapper.readTree(GeminiPrompt.RESPONSE_JSON_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini 응답 스키마를 읽을 수 없습니다.");
        }
    }

    @Override
    public AnalysisOutcome analyze(AnalysisInput input, Duration timeout) {
        GeminiGenerateContentResponse response = post(buildRequest(input), timeout);
        return convert(response);
    }

    private GeminiGenerateContentRequest buildRequest(AnalysisInput input) {
        List<GeminiPart> parts = new ArrayList<>();
        boolean hasText = input.sourceText() != null && !input.sourceText().isBlank();
        switch (input.source()) {
            case PHOTOS -> {
                parts.add(GeminiPart.text(GeminiPrompt.imageInstruction(input.images().size())));
                addImages(parts, input.images());
            }
            case YOUTUBE -> {
                parts.add(new GeminiPart(null, null, new GeminiFileData(null, input.videoUrl()),
                        new GeminiVideoMetadata(config.videoFps())));
                parts.add(GeminiPart.text(GeminiPrompt.videoInstruction(hasText)));
            }
            case INSTAGRAM_POST -> {
                parts.add(GeminiPart.text(GeminiPrompt.instagramPostInstruction(input.images().size(), hasText)));
                addImages(parts, input.images());
            }
            case INSTAGRAM_REEL -> {
                // fps 를 주지 않는다(기본 1fps). 72초 Reel 에서 fps 0.2 는 조리 단계가 5~6개에서 3개로 줄었다(2026-09-14 실측).
                parts.add(new GeminiPart(null, null, new GeminiFileData(VIDEO_MP4, input.videoUrl()), null));
                parts.add(GeminiPart.text(GeminiPrompt.instagramReelInstruction(hasText)));
            }
        }
        if (hasText) {
            String label = input.source() == AnalysisInput.Source.YOUTUBE ? "영상 설명란" : "게시물 캡션";
            parts.add(GeminiPart.text(GeminiPrompt.sourceText(label, input.sourceText())));
        }
        return new GeminiGenerateContentRequest(
                List.of(new GeminiContent("user", parts)),
                new GeminiContent(null, List.of(GeminiPart.text(GeminiPrompt.SYSTEM_INSTRUCTION))),
                new GeminiGenerationConfig("application/json", responseJsonSchema));
    }

    private static void addImages(List<GeminiPart> parts, List<InlineImage> images) {
        images.forEach(image -> parts.add(new GeminiPart(null,
                new GeminiInlineData(image.mimeType(), Base64.getEncoder().encodeToString(image.content())),
                null, null)));
    }

    private GeminiGenerateContentResponse post(GeminiGenerateContentRequest request, Duration timeout) {
        return call(() -> clientFor(timeout).post()
                .uri(config.baseUrl() + "/v1beta/models/" + config.model() + ":generateContent")
                .header("x-goog-api-key", config.apiKey())
                .body(request)
                .retrieve()
                .body(GeminiGenerateContentResponse.class));
    }

    @Override
    public UploadedVideo uploadVideo(Path file, long size, Duration timeout) {
        // 요청 두 번이 timeout 하나를 나눠 쓴다. 본문 요청에는 세션 시작 뒤 남은 시간만 준다.
        Instant uploadDeadline = Instant.now().plus(timeout);
        return call(() -> {
            String uploadUrl = clientFor(min(UPLOAD_START_TIMEOUT, timeout)).post()
                    .uri(URI.create(config.baseUrl() + "/upload/v1beta/files"))
                    .header("x-goog-api-key", config.apiKey())
                    .header("X-Goog-Upload-Protocol", "resumable")
                    .header("X-Goog-Upload-Command", "start")
                    .header("X-Goog-Upload-Header-Content-Length", Long.toString(size))
                    .header("X-Goog-Upload-Header-Content-Type", VIDEO_MP4)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("file", Map.of("display_name", "starpick-ingestion")))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getFirst("X-Goog-Upload-URL");
            URI session = uploadSession(uploadUrl);
            Duration remaining = Duration.between(Instant.now(), uploadDeadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "영상을 올릴 시간이 남지 않았습니다.", null);
            }
            // 업로드 주소 자체가 세션 토큰이다. 로그·예외에 남기지 않고 API 키도 보내지 않는다(실측과 같음).
            GeminiFileEnvelope uploaded = clientFor(remaining).post()
                    .uri(session)
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .contentType(MediaType.parseMediaType(VIDEO_MP4))
                    .body(new FileSystemResource(file))
                    .retrieve()
                    .body(GeminiFileEnvelope.class);
            GeminiFile uploadedFile = uploaded == null ? null : uploaded.file();
            if (uploadedFile == null || uploadedFile.name() == null) {
                throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "업로드 응답을 해석할 수 없습니다.", null);
            }
            UploadedVideo video = new UploadedVideo(uploadedFile.name(), uploadedFile.uri());
            if (uploadedFile.uri() == null) {
                // 파일은 이미 만들어졌다. 분석에 쓸 수 없으니 이름을 아는 지금 지운다.
                deleteQuietly(video);
                throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "업로드 응답을 해석할 수 없습니다.", null);
            }
            return video;
        });
    }

    private void deleteQuietly(UploadedVideo video) {
        try {
            deleteVideo(video, UPLOAD_START_TIMEOUT);
        } catch (RecipeAnalysisException ignored) {
            // 정리 실패는 분석 실패 원인이 아니다. Gemini 가 48시간 뒤 스스로 지운다.
        }
    }

    @Override
    public VideoFileState videoState(UploadedVideo video, Duration timeout) {
        GeminiFile file = call(() -> clientFor(timeout).get()
                .uri(URI.create(config.baseUrl() + "/v1beta/" + video.name()))
                .header("x-goog-api-key", config.apiKey())
                .retrieve()
                .body(GeminiFile.class));
        if (file == null) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "파일 상태 응답이 비었습니다.", null);
        }
        return switch (String.valueOf(file.state())) {
            case "ACTIVE" -> VideoFileState.ACTIVE;
            case "FAILED" -> VideoFileState.FAILED;
            default -> VideoFileState.PROCESSING;
        };
    }

    @Override
    public void deleteVideo(UploadedVideo video, Duration timeout) {
        call(() -> clientFor(timeout).delete()
                .uri(URI.create(config.baseUrl() + "/v1beta/" + video.name()))
                .header("x-goog-api-key", config.apiKey())
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * 응답 헤더로 받은 업로드 주소는 설정한 Gemini 주소와 스킴·호스트·포트가 같을 때만 쓴다.
     * 영상을 다른 곳으로 보내지 않고, 해석 예외에 주소가 실리지 않게 고정 메시지로 바꾼다.
     */
    private URI uploadSession(String uploadUrl) {
        URI base = URI.create(config.baseUrl());
        try {
            URI session = new URI(uploadUrl);
            if (base.getScheme().equalsIgnoreCase(session.getScheme())
                    && base.getHost().equalsIgnoreCase(session.getHost())
                    && base.getPort() == session.getPort()
                    && session.getRawUserInfo() == null) {
                return session;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // 아래 고정 메시지로 끝낸다.
        }
        throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "업로드 주소를 받지 못했습니다.", null);
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new RecipeAnalysisException(Kind.RETRYABLE, "Gemini 연결 또는 timeout 실패", null);
        } catch (RestClientResponseException e) {
            throw classifyHttpFailure(e);
        } catch (RestClientException e) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 응답을 해석할 수 없습니다.", null);
        }
    }

    private RestClient clientFor(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return restClientBuilder.clone().requestFactory(factory).build();
    }

    private RecipeAnalysisException classifyHttpFailure(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        if (status.is5xxServerError()) {
            return new RecipeAnalysisException(Kind.RETRYABLE, "Gemini 서버 오류: " + status.value(), null);
        }
        if (status.value() == 429) {
            Duration retryAfter = retryAfter(e.getResponseBodyAsString());
            return retryAfter == null
                    ? new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 요청 한도 오류: 429", null)
                    : new RecipeAnalysisException(Kind.RETRYABLE, "Gemini 요청 한도 오류: 429", retryAfter);
        }
        if (status.value() == 400 && isRejectedInput(e.getResponseBodyAsString())) {
            return new RecipeAnalysisException(Kind.INPUT_REJECTED, "Gemini 가 입력을 거절했습니다: 400", null);
        }
        return new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 요청 오류: " + status.value(), null);
    }

    /**
     * 입력 자체를 받아들이지 않은 400 인지. 잘못된 API 키도 같은 400 INVALID_ARGUMENT 로 오므로
     * ErrorInfo.reason 으로 걸러낸다 — 거르지 않으면 설정 사고가 "영상을 볼 수 없음"으로 감춰진다.
     */
    private boolean isRejectedInput(String errorBody) {
        try {
            JsonNode error = jsonMapper.readTree(errorBody).path("error");
            if (!"INVALID_ARGUMENT".equals(error.path("status").asString())) {
                return false;
            }
            for (JsonNode detail : error.path("details")) {
                if ("API_KEY_INVALID".equals(detail.path("reason").asString())) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            // 오류 본문은 외부 입력이다. 해석할 수 없으면 복구 불가능으로 둔다.
            return false;
        }
    }

    private Duration retryAfter(String errorBody) {
        try {
            JsonNode details = jsonMapper.readTree(errorBody).at("/error/details");
            if (!details.isArray()) {
                return null;
            }
            for (JsonNode detail : details) {
                if ("type.googleapis.com/google.rpc.RetryInfo".equals(detail.path("@type").asString())) {
                    String raw = detail.path("retryDelay").asString();
                    if (raw.endsWith("s")) {
                        return Duration.ofMillis(Math.round(Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1000));
                    }
                }
            }
        } catch (Exception ignored) {
            // 오류 본문 자체는 외부 입력이다. 파싱 실패는 RetryInfo 없음으로 취급한다.
        }
        return null;
    }

    private AnalysisOutcome convert(GeminiGenerateContentResponse response) {
        if (response == null) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 응답이 비었습니다.", null);
        }
        if (response.promptFeedback() != null && response.promptFeedback().blockReason() != null) {
            throw new RecipeAnalysisException(Kind.CONTENT_BLOCKED, "Gemini가 입력을 차단했습니다.", null);
        }
        if (response.candidates() == null || response.candidates().isEmpty()) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 후보 응답이 없습니다.", null);
        }
        GeminiGenerateContentResponse.Candidate candidate = response.candidates().getFirst();
        String finishReason = candidate.finishReason();
        if (BLOCKED_FINISH_REASONS.contains(finishReason)) {
            throw new RecipeAnalysisException(Kind.CONTENT_BLOCKED, "Gemini가 응답을 차단했습니다.", null);
        }
        if (!"STOP".equals(finishReason)) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 응답이 정상 종료되지 않았습니다.", null);
        }
        if (candidate.content() == null || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()
                || candidate.content().parts().getFirst().text() == null) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 응답 본문이 없습니다.", null);
        }

        GeminiRecipeDraft raw;
        try {
            raw = jsonMapper.readValue(candidate.content().parts().getFirst().text(), GeminiRecipeDraft.class);
        } catch (Exception e) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 응답을 해석할 수 없습니다.", null);
        }
        Verdict verdict;
        try {
            verdict = Verdict.valueOf(raw.verdict());
        } catch (RuntimeException e) {
            throw new RecipeAnalysisException(Kind.UNRECOVERABLE, "Gemini 판정값을 해석할 수 없습니다.", null);
        }
        TokenUsage usage = usage(response.usageMetadata());
        if (verdict != Verdict.RECIPE) {
            return new AnalysisOutcome(verdict, null, usage);
        }

        RecipeCategory category = null;
        try {
            if (raw.categoryCode() != null) {
                category = RecipeCategory.valueOf(raw.categoryCode());
            }
        } catch (IllegalArgumentException ignored) {
            category = null;
        }
        List<RecipeDraft.Ingredient> ingredients = raw.ingredients() == null ? List.of()
                : raw.ingredients().stream()
                        .map(value -> new RecipeDraft.Ingredient(null, value.name(), value.amountText()))
                        .toList();
        List<RecipeDraft.Step> steps = raw.steps() == null ? List.of()
                : raw.steps().stream().map(value -> new RecipeDraft.Step(value.content())).toList();
        RecipeDraft draft = new RecipeDraft(raw.title(), category, raw.cookTimeMinutes(), raw.servings(),
                ingredients, steps);
        return new AnalysisOutcome(verdict, draft, usage);
    }

    private TokenUsage usage(GeminiGenerateContentResponse.UsageMetadata value) {
        return value == null ? new TokenUsage(null, null)
                : new TokenUsage(value.promptTokenCount(), value.candidatesTokenCount());
    }
}
