package com.star_pick.starpick.domain.ingestion.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 분석 요청으로 받은 YouTube 영상 링크. 받는 형식과 저장 형식을 이 한 곳에서 정한다.
 *
 * <p>공유 추적 쿼리({@code si} 등)는 버린다. 쇼츠는 앱이 원본을 쇼츠 화면으로 열 수 있게 쇼츠 형식을 유지한다.
 * Gemini 는 두 형식을 모두 영상 입력으로 받는다(2026-09-13 실측).
 */
public record YouTubeUrl(String videoId, boolean shorts) {

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Set<String> WEB_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
    private static final String SHORTS_PATH = "/shorts/";

    public static Optional<YouTubeUrl> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getPath();
        if (scheme == null || host == null || path == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("youtu.be")) {
            // 경로가 없으면 getPath() 가 "" 라 substring 이 터진다. 경로는 정확히 "/{id}" 여야 한다.
            return path.length() > 1 ? of(path.substring(1), false) : Optional.empty();
        }
        if (!WEB_HOSTS.contains(host)) {
            return Optional.empty();
        }
        if (path.equals("/watch")) {
            return of(queryParameter(uri.getRawQuery(), "v"), false);
        }
        if (path.startsWith(SHORTS_PATH)) {
            String id = path.substring(SHORTS_PATH.length());
            return of(id.endsWith("/") ? id.substring(0, id.length() - 1) : id, true);
        }
        return Optional.empty();
    }

    public String canonicalUrl() {
        return shorts
                ? "https://www.youtube.com/shorts/" + videoId
                : "https://www.youtube.com/watch?v=" + videoId;
    }

    public String thumbnailUrl() {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    private static Optional<YouTubeUrl> of(String candidate, boolean shorts) {
        if (candidate == null || !VIDEO_ID.matcher(candidate).matches()) {
            return Optional.empty();
        }
        return Optional.of(new YouTubeUrl(candidate, shorts));
    }

    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equals(name)) {
                return pair.substring(separator + 1);
            }
        }
        return null;
    }
}
