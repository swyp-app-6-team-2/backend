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
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatusCode;
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
        boolean hasCaption = input.caption() != null && !input.caption().isBlank();
        switch (input.source()) {
            case PHOTOS -> {
                parts.add(GeminiPart.text(GeminiPrompt.imageInstruction(input.images().size())));
                addImages(parts, input.images());
            }
            case YOUTUBE -> {
                parts.add(new GeminiPart(null, null, new GeminiFileData(input.videoUrl()),
                        new GeminiVideoMetadata(config.videoFps())));
                parts.add(GeminiPart.text(GeminiPrompt.VIDEO_INSTRUCTION));
            }
            case INSTAGRAM_POST -> {
                parts.add(GeminiPart.text(GeminiPrompt.instagramPostInstruction(input.images().size(), hasCaption)));
                addImages(parts, input.images());
            }
        }
        if (hasCaption) {
            parts.add(GeminiPart.text(GeminiPrompt.captionData(input.caption())));
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
        try {
            return clientFor(timeout).post()
                    .uri(config.baseUrl() + "/v1beta/models/" + config.model() + ":generateContent")
                    .header("x-goog-api-key", config.apiKey())
                    .body(request)
                    .retrieve()
                    .body(GeminiGenerateContentResponse.class);
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
