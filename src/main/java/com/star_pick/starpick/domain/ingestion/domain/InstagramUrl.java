package com.star_pick.starpick.domain.ingestion.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 분석 요청으로 받은 Instagram 게시물·Reel 링크. 받는 형식과 저장 형식을 이 한 곳에서 정한다.
 *
 * <p>공유 추적 쿼리는 버리고 게시물의 {@code img_index}(1부터 센다, 2026-09-14 실측)만 남긴다.
 */
public record InstagramUrl(String shortcode, boolean reel, Integer imgIndex) {

    private static final Set<String> HOSTS = Set.of("instagram.com", "www.instagram.com", "m.instagram.com");
    private static final Pattern SHORTCODE = Pattern.compile("[A-Za-z0-9_-]{5,64}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._]{1,30}");
    private static final Pattern IMG_INDEX = Pattern.compile("[1-9][0-9]?");

    public static Optional<InstagramUrl> parse(String raw) {
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
        // 디코딩하지 않은 경로로 판정한다. %인코딩으로 코드·사용자명 규칙을 우회하지 못하게 한다.
        String path = uri.getRawPath();
        if (scheme == null || host == null || path == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String[] segments = trimmed.split("/", -1);
        // "/{사용자명}/p/{code}" 는 사용자명을 떼고 "/p/{code}" 로 본다.
        if (segments.length == 4 && segments[0].isEmpty() && USERNAME.matcher(segments[1]).matches()) {
            segments = new String[]{"", segments[2], segments[3]};
        }
        if (segments.length != 3 || !segments[0].isEmpty() || !SHORTCODE.matcher(segments[2]).matches()) {
            return Optional.empty();
        }
        if (segments[1].equals("reel")) {
            return Optional.of(new InstagramUrl(segments[2], true, null));
        }
        if (!segments[1].equals("p")) {
            return Optional.empty();
        }
        List<String> imgIndexes = queryValues(uri.getRawQuery(), "img_index");
        if (imgIndexes.isEmpty()) {
            return Optional.of(new InstagramUrl(segments[2], false, null));
        }
        // 값 없이 오거나 두 번 오면 어느 카드인지 알 수 없으므로 거절한다.
        return imgIndexes.size() == 1 && IMG_INDEX.matcher(imgIndexes.getFirst()).matches()
                ? Optional.of(new InstagramUrl(segments[2], false, Integer.valueOf(imgIndexes.getFirst())))
                : Optional.empty();
    }

    public String canonicalUrl() {
        if (reel) {
            return "https://www.instagram.com/reel/" + shortcode + "/";
        }
        return "https://www.instagram.com/p/" + shortcode + "/" + (imgIndex == null ? "" : "?img_index=" + imgIndex);
    }

    /** 이름이 같은 쿼리 항목의 값들. {@code "img_index"} 처럼 {@code =} 가 없으면 빈 문자열 값으로 센다. */
    private static List<String> queryValues(String rawQuery, String name) {
        if (rawQuery == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (key.equals(name)) {
                values.add(separator < 0 ? "" : pair.substring(separator + 1));
            }
        }
        return values;
    }
}
