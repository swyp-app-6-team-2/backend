package com.star_pick.starpick.domain.ingestion.infrastructure.instagram;

import static com.star_pick.starpick.domain.ingestion.service.InstagramFetchException.Kind.RETRYABLE;
import static com.star_pick.starpick.domain.ingestion.service.InstagramFetchException.Kind.TOO_LARGE;
import static com.star_pick.starpick.domain.ingestion.service.InstagramFetchException.Kind.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.star_pick.starpick.domain.ingestion.service.InlineImage;
import com.star_pick.starpick.domain.ingestion.service.InstagramFailure;
import com.star_pick.starpick.domain.ingestion.service.InstagramFetchException;
import com.star_pick.starpick.domain.ingestion.service.InstagramMedia;
import com.star_pick.starpick.domain.ingestion.service.InstagramPost;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class InstagramEmbedClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // 본문이 멈춘 응답이 다른 요청을 막지 않게 요청마다 스레드를 쓴다.
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        server.stop(0);
        // HttpServer#stop 은 넘겨받은 Executor 를 종료하지 않는다.
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("carousel embed 를 카드 순서대로 해석하고 caption 앞뒤 공백을 지운다")
    void parsesCarouselInCardOrder() {
        AtomicReference<String> userAgent = new AtomicReference<>();
        serve("/p/DKI9fBzy5FB/embed/captioned/", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            write(exchange, 200, "text/html", embedHtml("""
                    {"__typename":"GraphSidecar","display_url":"%1$s/cover.jpg",
                     "edge_media_to_caption":{"edges":[{"node":{"text":"  콩나물밥 레시피  "}}]},
                     "edge_sidecar_to_children":{"edges":[
                       {"node":{"is_video":false,"display_url":"%1$s/1.jpg"}},
                       {"node":{"is_video":true,"display_url":"%1$s/2.jpg","video_url":"%1$s/2.mp4"}},
                       {"node":{"is_video":false,"display_url":"%1$s/3.jpg"}}]}}
                    """.formatted(baseUrl)));
        });

        InstagramPost post = client().fetchPost("DKI9fBzy5FB", false, Duration.ofSeconds(2));

        assertThat(post.caption()).isEqualTo("콩나물밥 레시피");
        assertThat(post.media()).containsExactly(
                new InstagramMedia(false, baseUrl + "/1.jpg", null),
                new InstagramMedia(true, baseUrl + "/2.jpg", baseUrl + "/2.mp4"),
                new InstagramMedia(false, baseUrl + "/3.jpg", null));
        assertThat(userAgent.get()).isEqualTo("Mozilla/5.0");
    }

    @Test
    @DisplayName("Reel 은 /reel/ embed 를 읽고 단일 영상과 썸네일을 돌려준다")
    void parsesSingleReel() {
        serve("/reel/DcdllvBmOgm/embed/captioned/", exchange -> write(exchange, 200, "text/html", embedHtml("""
                {"__typename":"GraphVideo","is_video":true,"display_url":"%1$s/thumb.jpg","video_url":"%1$s/reel.mp4",
                 "edge_media_to_caption":{"edges":[]}}
                """.formatted(baseUrl))));

        InstagramPost post = client().fetchPost("DcdllvBmOgm", true, Duration.ofSeconds(2));

        assertThat(post.caption()).isNull();
        assertThat(post.media()).containsExactly(
                new InstagramMedia(true, baseUrl + "/thumb.jpg", baseUrl + "/reel.mp4"));
    }

    @Test
    @DisplayName("주소가 없거나 허용 밖이거나 형식이 깨진 카드는 게시물을 실패시키지 않고 자리를 지킨 채 주소만 null 이다")
    void keepsBrokenCardAddressesAsNull() {
        serve("/p/PARTIAL001/embed/captioned/", exchange -> write(exchange, 200, "text/html", embedHtml("""
                {"edge_sidecar_to_children":{"edges":[
                   {"node":{"is_video":false,"display_url":"%1$s/1.jpg"}},
                   {"node":{"is_video":true,"display_url":"https://evil.test/2.jpg"}},
                   {},
                   {"node":{"is_video":false}},
                   {"node":{"is_video":false,"display_url":"%1$s/5.jpg"}}]}}
                """.formatted(baseUrl))));

        InstagramPost post = client().fetchPost("PARTIAL001", false, Duration.ofSeconds(2));

        assertThat(post.media()).containsExactly(
                new InstagramMedia(false, baseUrl + "/1.jpg", null),
                new InstagramMedia(true, null, null),
                new InstagramMedia(false, null, null),
                new InstagramMedia(false, null, null),
                new InstagramMedia(false, baseUrl + "/5.jpg", null));
    }

    @Test
    @DisplayName("redirect·4xx·게시물 정보 없음은 수집 불가이고 redirect 를 따라가지 않는다")
    void classifiesUnavailableSources() {
        AtomicBoolean redirectFollowed = new AtomicBoolean();
        serve("/accounts/login", exchange -> {
            redirectFollowed.set(true);
            write(exchange, 200, "text/html", "login");
        });
        serve("/p/REDIRECT01/embed/captioned/", exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl + "/accounts/login");
            write(exchange, 302, "text/html", "");
        });
        serve("/p/NOTFOUND01/embed/captioned/", exchange -> write(exchange, 404, "text/html", "SENSITIVE_BODY"));
        serve("/p/LIMITED001/embed/captioned/", exchange -> write(exchange, 429, "text/html", "SENSITIVE_BODY"));
        serve("/p/NOCONTEXT1/embed/captioned/", exchange -> write(exchange, 200, "text/html", "<p>SENSITIVE_BODY</p>"));
        serve("/p/NOMEDIA001/embed/captioned/", exchange -> write(exchange, 200, "text/html", embedHtmlRaw("{\"gql_data\":null}")));
        serve("/p/UNCLOSED01/embed/captioned/", exchange -> write(exchange, 200, "text/html", "contextJSON\":\"{SENSITIVE_BODY"));

        for (String code : List.of("REDIRECT01", "NOTFOUND01", "LIMITED001", "NOCONTEXT1", "NOMEDIA001", "UNCLOSED01")) {
            InstagramFetchException failure = fetchFailure(code, Duration.ofSeconds(2));
            assertThat(failure.kind()).as(code).isEqualTo(UNAVAILABLE);
            assertThat(failure.getMessage()).as(code).doesNotContain("SENSITIVE_BODY", "localhost");
        }
        assertThat(redirectFollowed).isFalse();

        assertThatThrownBy(() -> client().fetchPost("REDIRECT01", false, Duration.ofSeconds(2)))
                .isInstanceOf(InstagramFetchException.class)
                .extracting(e -> ((InstagramFetchException) e).failure())
                .isEqualTo(InstagramFailure.BLOCKED);
        assertThatThrownBy(() -> client().fetchPost("LIMITED001", false, Duration.ofSeconds(2)))
                .isInstanceOf(InstagramFetchException.class)
                .extracting(e -> ((InstagramFetchException) e).failure())
                .isEqualTo(InstagramFailure.BLOCKED);
        assertThatThrownBy(() -> client().fetchPost("NOTFOUND01", false, Duration.ofSeconds(2)))
                .isInstanceOf(InstagramFetchException.class)
                .extracting(e -> ((InstagramFetchException) e).failure())
                .isEqualTo(InstagramFailure.NOT_FOUND);
    }

    @Test
    @DisplayName("깨진 embed 는 없는 게시물과 구분한다")
    void classifiesBrokenEmbed() {
        serve("/p/BROKEN0001/embed/captioned/", exchange ->
                write(exchange, 200, "text/html", "<div class=\"EmbedBrokenMedia\"></div>"));

        assertThatThrownBy(() -> client().fetchPost("BROKEN0001", false, Duration.ofSeconds(2)))
                .isInstanceOf(InstagramFetchException.class)
                .extracting(e -> ((InstagramFetchException) e).failure())
                .isEqualTo(InstagramFailure.EMBED_BROKEN);
    }

    @Test
    @DisplayName("5xx 와 본문이 멈춘 응답은 재시도 가능이고, 멈춘 본문은 timeout 안에 끊긴다")
    void classifiesRetryableFailures() {
        serve("/p/SERVERERR1/embed/captioned/", exchange -> write(exchange, 503, "text/html", "SENSITIVE_BODY"));
        serve("/p/STALLED001/embed/captioned/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("<html>".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        assertThat(fetchFailure("SERVERERR1", Duration.ofSeconds(2)).kind()).isEqualTo(RETRYABLE);
        long startedNanos = System.nanoTime();
        assertThat(fetchFailure("STALLED001", Duration.ofMillis(500)).kind()).isEqualTo(RETRYABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("이미지는 Referer 를 붙여 받고 형식·주소·상한을 확인한다")
    void downloadsImageWithinLimit() {
        AtomicReference<String> referer = new AtomicReference<>();
        serve("/ok.jpg", exchange -> {
            referer.set(exchange.getRequestHeaders().getFirst("Referer"));
            writeBytes(exchange, 200, "image/jpeg", new byte[]{1, 2, 3});
        });
        serve("/page.html", exchange -> write(exchange, 200, "text/html", "<html>"));
        serve("/empty.jpg", exchange -> writeBytes(exchange, 200, "image/jpeg", new byte[0]));
        serve("/declared-large.jpg", exchange -> writeBytes(exchange, 200, "image/jpeg", new byte[10]));
        serve("/chunked-large.jpg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[10]);
            exchange.close();
        });

        InlineImage image = client().downloadImage(baseUrl + "/ok.jpg", 3, Duration.ofSeconds(2));

        assertThat(image.mimeType()).isEqualTo("image/jpeg");
        assertThat(image.content()).containsExactly(1, 2, 3);
        assertThat(referer.get()).isEqualTo("https://www.instagram.com/");
        assertThat(imageFailure(baseUrl + "/page.html").kind()).isEqualTo(UNAVAILABLE);
        assertThat(imageFailure(baseUrl + "/empty.jpg").kind()).isEqualTo(UNAVAILABLE);
        assertThat(imageFailure(baseUrl + "/declared-large.jpg").kind()).isEqualTo(TOO_LARGE);
        assertThat(imageFailure(baseUrl + "/chunked-large.jpg").kind()).isEqualTo(TOO_LARGE);
        assertThat(imageFailure("https://evil.test/1.jpg").kind()).isEqualTo(UNAVAILABLE);
    }

    @Test
    @DisplayName("영상은 mp4 만 파일로 받고, 선언 길이든 스트림이든 상한을 넘으면 멈추며, 빈 영상은 수집 불가다")
    void downloadsVideoToFile(@TempDir Path dir) throws IOException {
        serve("/reel.mp4", exchange -> writeBytes(exchange, 200, "video/mp4", new byte[]{7, 7, 7, 7}));
        serve("/chunked.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[]{7, 7, 7, 7});
            exchange.close();
        });
        serve("/empty.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        serve("/not-video.mp4", exchange -> writeBytes(exchange, 200, "image/jpeg", new byte[]{1}));
        Path target = dir.resolve("reel.mp4");

        assertThat(client().downloadVideo(baseUrl + "/reel.mp4", target, 4, Duration.ofSeconds(2))).isEqualTo(4);
        assertThat(Files.readAllBytes(target)).containsExactly(7, 7, 7, 7);

        assertThat(videoFailure(baseUrl + "/reel.mp4", target).kind()).isEqualTo(TOO_LARGE);
        assertThat(videoFailure(baseUrl + "/chunked.mp4", target).kind()).isEqualTo(TOO_LARGE);
        assertThat(videoFailure(baseUrl + "/empty.mp4", target).kind()).isEqualTo(UNAVAILABLE);
        assertThat(videoFailure(baseUrl + "/not-video.mp4", target).kind()).isEqualTo(UNAVAILABLE);
    }

    @Test
    @DisplayName("상한을 넘는 응답은 남은 본문을 받지 않고 곧바로 끊는다")
    void stopsReadingOverLimitResponse(@TempDir Path dir) {
        HttpHandler slowLarge = exchange -> {
            exchange.getResponseHeaders().set("Content-Type",
                    exchange.getRequestURI().getPath().endsWith(".mp4") ? "video/mp4" : "image/jpeg");
            exchange.sendResponseHeaders(200, 50_000_000);
            try {
                for (int i = 0; i < 200; i++) {
                    exchange.getResponseBody().write(new byte[1024]);
                    exchange.getResponseBody().flush();
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException ignored) {
                // 클라이언트가 끊으면 여기로 온다.
            }
            exchange.close();
        };
        serve("/slow-large.jpg", slowLarge);
        serve("/slow-large.mp4", slowLarge);

        long startedNanos = System.nanoTime();
        assertThat(imageFailure(baseUrl + "/slow-large.jpg").kind()).isEqualTo(TOO_LARGE);
        assertThat(catchThrowableOfType(InstagramFetchException.class, () -> client().downloadVideo(
                baseUrl + "/slow-large.mp4", dir.resolve("v.mp4"), 3, Duration.ofSeconds(8))).kind()).isEqualTo(TOO_LARGE);
        assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @CsvSource({
            "https://scontent-ssn1-1.cdninstagram.com/v/t51/1.jpg, true",
            "https://scontent-nrt1-2.cdninstagram.com/o1/v/t2/reel.mp4, true",
            "https://video.fbcdn.net/v/reel.mp4, true",
            "http://scontent-ssn1-1.cdninstagram.com/1.jpg, false",
            "https://cdninstagram.com.evil.test/1.jpg, false",
            "https://evilcdninstagram.com/1.jpg, false",
            "https://www.instagram.com/p/DKI9fBzy5FB/, false"
    })
    @DisplayName("운영 미디어 주소는 https 의 Instagram CDN 호스트만 허용한다")
    void allowsOnlyInstagramCdnOverHttps(String url, boolean allowed) {
        assertThat(InstagramEmbedClient.isAllowedMediaUrl(URI.create(url))).isEqualTo(allowed);
    }

    private InstagramEmbedClient client() {
        return new InstagramEmbedClient(RestClient.builder(),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                JSON, baseUrl, uri -> "localhost".equals(uri.getHost()));
    }

    private InstagramFetchException fetchFailure(String code, Duration timeout) {
        return catchThrowableOfType(InstagramFetchException.class, () -> client().fetchPost(code, false, timeout));
    }

    private InstagramFetchException imageFailure(String url) {
        return catchThrowableOfType(InstagramFetchException.class,
                () -> client().downloadImage(url, 3, Duration.ofSeconds(8)));
    }

    private InstagramFetchException videoFailure(String url, Path target) {
        return catchThrowableOfType(InstagramFetchException.class,
                () -> client().downloadVideo(url, target, 3, Duration.ofSeconds(2)));
    }

    private void serve(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    /** 실제 embed 처럼 context JSON 을 한 번 더 JSON 문자열로 감싸 넣는다. */
    private static String embedHtml(String shortcodeMedia) {
        return embedHtmlRaw("{\"gql_data\":{\"shortcode_media\":" + shortcodeMedia + "}}");
    }

    private static String embedHtmlRaw(String context) {
        return "<html><script>s.handle({\"contextJSON\":" + JSON.writeValueAsString(context) + "})</script></html>";
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        writeBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // HttpServer 에서 길이 0 은 chunked 라 본문이 없으면 -1 을 준다.
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
