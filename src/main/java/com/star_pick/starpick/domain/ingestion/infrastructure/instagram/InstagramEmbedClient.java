package com.star_pick.starpick.domain.ingestion.infrastructure.instagram;

import com.star_pick.starpick.domain.ingestion.service.InlineImage;
import com.star_pick.starpick.domain.ingestion.service.InstagramClient;
import com.star_pick.starpick.domain.ingestion.service.InstagramFetchException;
import com.star_pick.starpick.domain.ingestion.service.InstagramFetchException.Kind;
import com.star_pick.starpick.domain.ingestion.service.InstagramMedia;
import com.star_pick.starpick.domain.ingestion.service.InstagramPost;
import com.star_pick.starpick.domain.upload.service.SupportedImageContentTypes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공개 embed({@code /embed/captioned/})의 {@code contextJSON} 에서 caption·미디어를 읽고 CDN 에서 받는다.
 * embed 구조와 CDN 규칙은 이 클래스 밖으로 나가지 않는다.
 */
class InstagramEmbedClient implements InstagramClient {

    private static final String USER_AGENT = "Mozilla/5.0";
    private static final String REFERER = "https://www.instagram.com/";
    private static final String CONTEXT_MARKER = "contextJSON\":\"";
    /** 실측 embed HTML 은 28만~47만 byte 였다. */
    private static final int MAX_EMBED_BYTES = 2 * 1024 * 1024;
    private static final List<String> MEDIA_HOST_SUFFIXES = List.of(".cdninstagram.com", ".fbcdn.net");

    private final RestClient.Builder restClientBuilder;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String embedBaseUrl;
    private final Predicate<URI> mediaUrlPolicy;

    InstagramEmbedClient(RestClient.Builder restClientBuilder, HttpClient httpClient, JsonMapper jsonMapper,
                         String embedBaseUrl, Predicate<URI> mediaUrlPolicy) {
        this.restClientBuilder = restClientBuilder;
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
        this.embedBaseUrl = embedBaseUrl;
        this.mediaUrlPolicy = mediaUrlPolicy;
    }

    /** 운영 규칙: https 이고 Instagram CDN 호스트. embed 가 준 주소라도 이 밖이면 받지도, 미리보기로 내보내지도 않는다. */
    static boolean isAllowedMediaUrl(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return MEDIA_HOST_SUFFIXES.stream()
                .anyMatch(suffix -> host.endsWith(suffix) || host.equals(suffix.substring(1)));
    }

    @Override
    public InstagramPost fetchPost(String shortcode, boolean reel, Duration timeout) {
        URI embed = URI.create(embedBaseUrl + (reel ? "/reel/" : "/p/") + shortcode + "/embed/captioned/");
        byte[] html = get(embed, null, timeout, (request, response) -> {
            requireOk(response);
            return readLimited(response, MAX_EMBED_BYTES, Kind.UNAVAILABLE);
        });
        return parse(new String(html, StandardCharsets.UTF_8));
    }

    @Override
    public InlineImage downloadImage(String mediaUrl, long maxBytes, Duration timeout) {
        return get(mediaUri(mediaUrl), REFERER, timeout, (request, response) -> {
            requireOk(response);
            String contentType = mediaType(response);
            if (!SupportedImageContentTypes.matches(contentType)) {
                throw unavailable("지원하지 않는 이미지 형식입니다.");
            }
            byte[] content = readLimited(response, maxBytes, Kind.TOO_LARGE);
            if (content.length == 0) {
                throw unavailable("이미지 응답이 비었습니다.");
            }
            return new InlineImage(contentType, content);
        });
    }

    @Override
    public long downloadVideo(String mediaUrl, Path target, long maxBytes, Duration timeout) {
        return get(mediaUri(mediaUrl), REFERER, timeout, (request, response) -> {
            requireOk(response);
            if (!"video/mp4".equals(mediaType(response))) {
                throw unavailable("지원하지 않는 영상 형식입니다.");
            }
            if (response.getHeaders().getContentLength() > maxBytes) {
                throw new InstagramFetchException(Kind.TOO_LARGE, "영상이 상한을 넘었습니다.");
            }
            return copyLimited(response.getBody(), target, maxBytes);
        });
    }

    private <T> T get(URI uri, String referer, Duration timeout, RestClient.RequestHeadersSpec.ExchangeFunction<T> reader) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        try {
            return restClientBuilder.clone().requestFactory(factory).build()
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .headers(headers -> {
                        if (referer != null) {
                            headers.set(HttpHeaders.REFERER, referer);
                        }
                    })
                    // 응답 닫기를 RestClient 에 맡기지 않는다. 기본 close 는 남은 본문을 끝까지 읽은 뒤 닫아서,
                    // 상한 초과·실패 응답도 timeout 까지 계속 받는다. 스트림을 바로 닫으면 JDK 가 구독을 취소한다.
                    .exchange((request, response) -> {
                        try {
                            return reader.exchange(request, response);
                        } finally {
                            response.getBody().close();
                        }
                    }, false);
        } catch (InstagramFetchException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new InstagramFetchException(Kind.RETRYABLE, "Instagram 연결 또는 timeout 실패");
        } catch (RestClientException e) {
            throw unavailable("Instagram 응답을 읽을 수 없습니다.");
        }
    }

    InstagramPost parse(String html) {
        int start = html.indexOf(CONTEXT_MARKER);
        if (start < 0) {
            throw unavailable("embed 에 게시물 정보가 없습니다.");
        }
        int from = start + CONTEXT_MARKER.length();
        int end = closingQuote(html, from);
        try {
            // HTML 속 JSON 문자열 리터럴이라 한 번 풀어야 JSON 이 된다.
            String context = jsonMapper.readValue("\"" + html.substring(from, end) + "\"", String.class);
            JsonNode shortcodeMedia = jsonMapper.readTree(context).path("gql_data").path("shortcode_media");
            if (!shortcodeMedia.isObject()) {
                throw unavailable("embed 에 게시물 정보가 없습니다.");
            }
            List<JsonNode> nodes = new ArrayList<>();
            for (JsonNode edge : shortcodeMedia.path("edge_sidecar_to_children").path("edges")) {
                // 형식이 깨진 항목도 자리를 남긴다(주소 없는 카드가 된다). 건너뛰면 뒤 카드 번호가 당겨져
                // img_index 가 다른 카드를 가리킨다.
                nodes.add(edge.path("node"));
            }
            if (nodes.isEmpty()) {
                nodes.add(shortcodeMedia);
            }
            List<InstagramMedia> media = new ArrayList<>(nodes.size());
            for (JsonNode node : nodes) {
                media.add(toMedia(node));
            }
            return new InstagramPost(caption(shortcodeMedia), media);
        } catch (JacksonException e) {
            throw unavailable("embed 게시물 정보를 해석할 수 없습니다.");
        }
    }

    /**
     * 이스케이프되지 않은 닫는 따옴표 위치. PoC 의 정규식을 Java 로 옮기면 반복마다 재귀가 쌓여
     * 수십만 자 입력에서 StackOverflowError 가 날 수 있어 직접 훑는다.
     */
    private static int closingQuote(String text, int from) {
        boolean escaped = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        throw unavailable("embed 게시물 정보가 끝나지 않습니다.");
    }

    /** 주소가 없거나 허용 밖이면 그 주소만 null 로 둔다. 쓰지 않을 카드 때문에 게시물 전체를 실패시키지 않는다. */
    private InstagramMedia toMedia(JsonNode node) {
        boolean video = node.path("is_video").isBoolean() && node.path("is_video").booleanValue();
        String displayUrl = allowedOrNull(node.path("display_url").asString());
        String videoUrl = video ? allowedOrNull(node.path("video_url").asString()) : null;
        return new InstagramMedia(video, displayUrl, videoUrl);
    }

    private String allowedOrNull(String mediaUrl) {
        if (mediaUrl.isBlank()) {
            return null;
        }
        try {
            return mediaUrlPolicy.test(new URI(mediaUrl)) ? mediaUrl : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String caption(JsonNode shortcodeMedia) {
        for (JsonNode edge : shortcodeMedia.path("edge_media_to_caption").path("edges")) {
            String text = edge.path("node").path("text").asString();
            if (!text.isBlank()) {
                return text.strip();
            }
        }
        return null;
    }

    private URI mediaUri(String mediaUrl) {
        try {
            URI uri = new URI(mediaUrl);
            if (mediaUrlPolicy.test(uri)) {
                return uri;
            }
        } catch (URISyntaxException ignored) {
            // embed 가 준 값도 외부 입력이다.
        }
        throw unavailable("허용하지 않는 미디어 주소입니다.");
    }

    private static void requireOk(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status >= 500) {
            throw new InstagramFetchException(Kind.RETRYABLE, "Instagram 서버 오류: " + status);
        }
        if (status != 200) {
            throw unavailable("Instagram 응답 상태: " + status);
        }
    }

    private static String mediaType(ClientHttpResponse response) {
        MediaType type = response.getHeaders().getContentType();
        return type == null ? null : (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
    }

    /** {@code maxBytes} 는 0 이상이어야 한다(호출자 책임). */
    private static byte[] readLimited(ClientHttpResponse response, long maxBytes, Kind overLimit) throws IOException {
        if (response.getHeaders().getContentLength() > maxBytes) {
            throw new InstagramFetchException(overLimit, "응답이 상한을 넘었습니다.");
        }
        byte[] content = response.getBody().readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
        if (content.length > maxBytes) {
            throw new InstagramFetchException(overLimit, "응답이 상한을 넘었습니다.");
        }
        return content;
    }

    /**
     * 네트워크 읽기 오류는 IOException 그대로 두어 재시도 가능으로 분류되게 하고, 파일 쓰기 오류는
     * {@link UncheckedIOException} 으로 감싼다 — 디스크 문제를 "Instagram 재시도 실패"로 기록하지 않기 위해서다.
     */
    private static long copyLimited(InputStream in, Path target, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = openForWrite(target)) {
            for (int read; (read = in.read(buffer)) != -1; ) {
                total += read;
                if (total > maxBytes) {
                    throw new InstagramFetchException(Kind.TOO_LARGE, "영상이 상한을 넘었습니다.");
                }
                write(out, buffer, read);
            }
        }
        if (total == 0) {
            throw unavailable("영상 응답이 비었습니다.");
        }
        return total;
    }

    private static OutputStream openForWrite(Path target) {
        try {
            return Files.newOutputStream(target);
        } catch (IOException e) {
            throw new UncheckedIOException("영상 임시 파일을 열 수 없습니다.", e);
        }
    }

    private static void write(OutputStream out, byte[] buffer, int length) {
        try {
            out.write(buffer, 0, length);
        } catch (IOException e) {
            throw new UncheckedIOException("영상 임시 파일에 쓸 수 없습니다.", e);
        }
    }

    private static InstagramFetchException unavailable(String message) {
        return new InstagramFetchException(Kind.UNAVAILABLE, message);
    }
}
