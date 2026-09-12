package com.star_pick.starpick.domain.ingestion.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.AnalysisInput;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.InlineImage;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalysisException;
import com.star_pick.starpick.domain.ingestion.service.Verdict;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class GeminiRecipeAnalyzerTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("사진을 inlineData와 구조화 출력 스키마로 보내고 RecipeDraft를 변환한다")
    void analyzesInlineImages() throws Exception {
        respond(200, """
                {"candidates":[{"content":{"parts":[{"text":"{\\"verdict\\":\\"RECIPE\\",\\"title\\":\\"감자전\\",\\"categoryCode\\":\\"KOREAN\\",\\"cookTimeMinutes\\":30,\\"servings\\":2,\\"ingredients\\":[{\\"name\\":\\"감자\\",\\"amountText\\":\\"2개\\"}],\\"steps\\":[{\\"content\\":\\"감자를 간다.\\"}]}"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1343,"candidatesTokenCount":440}}
                """);

        var outcome = analyzer().analyze(
                new AnalysisInput(List.of(new InlineImage("image/jpeg", new byte[]{1, 2, 3}))),
                Duration.ofSeconds(2));

        assertThat(outcome.verdict()).isEqualTo(Verdict.RECIPE);
        assertThat(outcome.draft().title()).isEqualTo("감자전");
        assertThat(outcome.draft().categoryCode().name()).isEqualTo("KOREAN");
        assertThat(outcome.draft().ingredients().getFirst().ingredientId()).isNull();
        assertThat(outcome.usage().prompt()).isEqualTo(1343);
        assertThat(apiKey.get()).isEqualTo("test-key");
        assertThat(requestBody.get())
                .contains("systemInstruction", "responseJsonSchema", "application/json")
                .contains("입력: 이미지 1장", "AQID", "image/jpeg");
        var request = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(request.at("/systemInstruction").has("role")).isFalse();
        assertThat(request.at("/contents/0/parts/0").has("inlineData")).isFalse();
        assertThat(request.at("/contents/0/parts/1").has("text")).isFalse();
    }

    @Test
    @DisplayName("레시피가 아니라는 판정은 초안 없이 반환한다")
    void returnsNoDraftForNotRecipe() {
        respond(200, candidateResponse("{\"verdict\":\"NOT_RECIPE\"}", "STOP"));

        AnalysisOutcome outcome = analyze(Duration.ofSeconds(1));

        assertThat(outcome.verdict()).isEqualTo(Verdict.NOT_RECIPE);
        assertThat(outcome.draft()).isNull();
    }

    @Test
    @DisplayName("알 수 없는 카테고리는 null로 낮추고 나머지 초안은 유지한다")
    void ignoresUnknownCategory() {
        respond(200, candidateResponse(
                "{\"verdict\":\"RECIPE\",\"title\":\"요리\",\"categoryCode\":\"FUSION\",\"ingredients\":[],\"steps\":[]}",
                "STOP"));

        AnalysisOutcome outcome = analyze(Duration.ofSeconds(1));

        assertThat(outcome.draft().title()).isEqualTo("요리");
        assertThat(outcome.draft().categoryCode()).isNull();
    }

    @Test
    @DisplayName("안전 필터 finishReason과 promptFeedback 차단을 구분한다")
    void classifiesBlockedContent() {
        respond(200, """
                {"candidates":[{"finishReason":"SAFETY"}]}
                """);
        assertFailure(RecipeAnalysisException.Kind.CONTENT_BLOCKED, Duration.ofSeconds(1));

        restartWith(200, """
                {"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}
                """);
        assertFailure(RecipeAnalysisException.Kind.CONTENT_BLOCKED, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("불완전하거나 후보가 없는 응답은 복구 불가능으로 분류한다")
    void classifiesIncompleteResponses() {
        respond(200, """
                {"candidates":[{"finishReason":"MAX_TOKENS"}]}
                """);
        assertFailure(RecipeAnalysisException.Kind.UNRECOVERABLE, Duration.ofSeconds(1));

        restartWith(200, "{\"candidates\":[]}");
        assertFailure(RecipeAnalysisException.Kind.UNRECOVERABLE, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("429는 RetryInfo가 있을 때만 재시도하며 지연 시간을 전달한다")
    void classifiesRateLimits() {
        respond(429, """
                {"error":{"message":"SENSITIVE_BODY","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7s"}]}}
                """);

        RecipeAnalysisException retryable = failure(Duration.ofSeconds(1));
        assertThat(retryable.kind()).isEqualTo(RecipeAnalysisException.Kind.RETRYABLE);
        assertThat(retryable.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertNoResponseBodyLeak(retryable);

        restartWith(429, "{\"error\":{\"message\":\"SENSITIVE_BODY\"}}");
        RecipeAnalysisException unrecoverable = failure(Duration.ofSeconds(1));
        assertThat(unrecoverable.kind()).isEqualTo(RecipeAnalysisException.Kind.UNRECOVERABLE);
        assertThat(unrecoverable.retryAfter()).isNull();
        assertNoResponseBodyLeak(unrecoverable);
    }

    @Test
    @DisplayName("서버 오류는 재시도하고 요청 오류는 재시도하지 않는다")
    void classifiesHttpErrors() {
        respond(503, "{\"error\":{\"message\":\"SENSITIVE_BODY\"}}");
        assertFailure(RecipeAnalysisException.Kind.RETRYABLE, Duration.ofSeconds(1));

        restartWith(400, "{\"error\":{\"message\":\"SENSITIVE_BODY\"}}");
        assertFailure(RecipeAnalysisException.Kind.UNRECOVERABLE, Duration.ofSeconds(1));

        restartWith(401, "{\"error\":{\"message\":\"SENSITIVE_BODY\"}}");
        assertFailure(RecipeAnalysisException.Kind.UNRECOVERABLE, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("응답 지연이 호출별 timeout을 넘으면 재시도 가능한 실패다")
    void classifiesTimeout() {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            try {
                Thread.sleep(200);
                write(exchange, 200, "{}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // client timeout 뒤 끊긴 응답 스트림이다.
            }
        });
        server.start();

        assertFailure(RecipeAnalysisException.Kind.RETRYABLE, Duration.ofMillis(20));
    }

    @Test
    @DisplayName("200 응답 본문이 JSON이 아니면 복구 불가능이고 원문을 노출하지 않는다")
    void classifiesInvalidJsonWithoutLeakingBody() {
        respond(200, "SENSITIVE_BODY_NOT_JSON");

        RecipeAnalysisException exception = failure(Duration.ofSeconds(1));

        assertThat(exception.kind()).isEqualTo(RecipeAnalysisException.Kind.UNRECOVERABLE);
        assertNoResponseBodyLeak(exception);
    }

    private GeminiRecipeAnalyzer analyzer() {
        return new GeminiRecipeAnalyzer(
                RestClient.builder(), HttpClient.newHttpClient(), JsonMapper.builder().build(),
                new IngestionProperties.Gemini("test-key", "test-model", baseUrl, Duration.ofSeconds(5)));
    }

    private AnalysisOutcome analyze(Duration timeout) {
        return analyzer().analyze(
                new AnalysisInput(List.of(new InlineImage("image/jpeg", new byte[]{1}))), timeout);
    }

    private RecipeAnalysisException failure(Duration timeout) {
        return catchThrowableOfType(RecipeAnalysisException.class, () -> analyze(timeout));
    }

    private void assertFailure(RecipeAnalysisException.Kind kind, Duration timeout) {
        RecipeAnalysisException exception = failure(timeout);
        assertThat(exception.kind()).isEqualTo(kind);
        assertNoResponseBodyLeak(exception);
    }

    private void assertNoResponseBodyLeak(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            assertThat(current.getMessage()).doesNotContain("SENSITIVE_BODY");
        }
    }

    private void restartWith(int status, String body) {
        server.stop(0);
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        respond(status, body);
    }

    private String candidateResponse(String generatedText, String finishReason) {
        try {
            String encodedText = JsonMapper.builder().build().writeValueAsString(generatedText);
            return """
                    {"candidates":[{"content":{"parts":[{"text":%s}]},"finishReason":"%s"}]}
                    """.formatted(encodedText, finishReason);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void respond(int status, String body) {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> write(exchange, status, body));
        server.start();
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        apiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
