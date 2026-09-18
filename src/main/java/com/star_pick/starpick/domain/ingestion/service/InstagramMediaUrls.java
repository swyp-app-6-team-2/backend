package com.star_pick.starpick.domain.ingestion.service;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 받을 수 있는 Instagram 미디어 주소. embed 가 준 주소든 보조 수집기가 준 주소든 같은 규칙을 통과해야 한다.
 * 허용 밖 주소는 받지도, 미리보기로 내보내지도 않는다.
 */
public final class InstagramMediaUrls {

    private static final List<String> HOST_SUFFIXES = List.of(".cdninstagram.com", ".fbcdn.net");

    private InstagramMediaUrls() {
    }

    public static boolean isAllowed(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return HOST_SUFFIXES.stream().anyMatch(suffix -> host.endsWith(suffix) || host.equals(suffix.substring(1)));
    }
}
