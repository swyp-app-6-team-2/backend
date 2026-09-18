package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.domain.ingestion.service.ReelVideo;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ApifyReelClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String REEL_URL = "https://www.instagram.com/reel/ABC1234defg/";

    private HttpServer server;
    private String baseUrl;
    private volatile String lastBody;
    private volatile String lastAuthorization;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("영상 주소와 캡션을 읽는다")
    void readsVideoUrl() {
        respond(200, """
                [{"videoUrl":"https://scontent-nrt1-1.cdninstagram.com/v/x.mp4",
                  "displayUrl":"https://scontent-nrt1-1.cdninstagram.com/v/t.jpg",
                  "caption":"재료: 감자 2개"}]
                """);

        Optional<ReelVideo> video = client().resolve(REEL_URL, TIMEOUT);

        assertThat(video).isPresent();
        assertThat(video.orElseThrow().videoUrl()).isEqualTo("https://scontent-nrt1-1.cdninstagram.com/v/x.mp4");
        assertThat(video.orElseThrow().caption()).isEqualTo("재료: 감자 2개");
        assertThat(video.orElseThrow().thumbnailUrl()).isEqualTo("https://scontent-nrt1-1.cdninstagram.com/v/t.jpg");
        assertThat(lastBody).contains(REEL_URL);
        assertThat(lastAuthorization).isEqualTo("Bearer test-token");
    }

    @Test
    @DisplayName("허용하지 않는 호스트 주소는 버린다")
    void rejectsForeignHost() {
        respond(200, "[{\"videoUrl\":\"https://evil.test/x.mp4\",\"caption\":\"c\"}]");

        assertThat(client().resolve(REEL_URL, TIMEOUT)).isEmpty();
    }

    @Test
    @DisplayName("빈 결과면 빈 값이다")
    void emptyOnNoItem() {
        respond(200, "[]");

        assertThat(client().resolve(REEL_URL, TIMEOUT)).isEmpty();
    }

    @Test
    @DisplayName("오류 응답이면 호출 실패 예외다")
    void throwsOnFailure() {
        respond(500, "boom");

        assertThatThrownBy(() -> client().resolve(REEL_URL, TIMEOUT))
                .isInstanceOf(ApifyUnavailableException.class);
    }

    private ApifyReelClient client() {
        return new ApifyReelClient(RestClient.builder(), JsonMapper.builder().build(),
                baseUrl, "apify~instagram-reel-scraper", "test-token");
    }

    private void respond(int status, String body) {
        server.createContext("/", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
