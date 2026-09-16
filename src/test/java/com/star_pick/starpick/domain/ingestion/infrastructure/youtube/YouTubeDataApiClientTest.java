package com.star_pick.starpick.domain.ingestion.infrastructure.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class YouTubeDataApiClientTest {

    private static final String API_KEY = "test-key";

    private HttpServer server;
    private ExecutorService executor;
    private YouTubeDataApiClient client;

    private final AtomicReference<String> requestUri = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";
    private volatile long delayMillis = 0;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/youtube/v3/videos", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            // Content-Type 이 없으면 메시지 컨버터가 본문 읽기를 거부한다.
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        client = new YouTubeDataApiClient(RestClient.builder(), HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort(), API_KEY);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    @DisplayName("설명란을 읽는다")
    void readsDescription() {
        body = "{\"items\":[{\"snippet\":{\"description\":\"재료\\n간장 3T\"}}]}";

        assertThat(client.description("kjG6h_LTklo", Duration.ofSeconds(5))).isEqualTo("재료\n간장 3T");
    }

    @Test
    @DisplayName("영상이 없으면 null 이다")
    void returnsNullWhenNoItems() {
        body = "{\"items\":[]}";

        assertThat(client.description("kjG6h_LTklo", Duration.ofSeconds(5))).isNull();
    }

    @Test
    @DisplayName("설명란이 비어 있으면 null 이다")
    void returnsNullWhenDescriptionBlank() {
        body = "{\"items\":[{\"snippet\":{\"description\":\"   \"}}]}";

        assertThat(client.description("kjG6h_LTklo", Duration.ofSeconds(5))).isNull();
    }

    @Test
    @DisplayName("키를 헤더로 보내고 URL 에는 넣지 않는다")
    void sendsKeyAsHeader() {
        body = "{\"items\":[]}";

        client.description("kjG6h_LTklo", Duration.ofSeconds(5));

        assertThat(apiKeyHeader.get()).isEqualTo(API_KEY);
        assertThat(requestUri.get())
                .contains("id=kjG6h_LTklo")
                .contains("part=snippet")
                .doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("오류 응답은 예외로 올린다")
    void throwsOnErrorStatus() {
        status = 403;
        body = "{\"error\":{\"code\":403,\"message\":\"quota exceeded\"}}";

        assertThatThrownBy(() -> client.description("kjG6h_LTklo", Duration.ofSeconds(5)))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("timeout 은 예외로 올린다")
    void throwsOnTimeout() {
        delayMillis = 500;
        body = "{\"items\":[]}";

        assertThatThrownBy(() -> client.description("kjG6h_LTklo", Duration.ofMillis(50)))
                .isInstanceOf(RestClientException.class);
    }
}
