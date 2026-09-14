package com.star_pick.starpick.domain.ingestion.service;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Instagram 공개 embed 수집. embed 구조·CDN 규칙·HTTP 세부는 구현에만 둔다.
 * 호출마다 외부 요청 한 번이고, 재시도·deadline 은 Worker 가 정한다.
 */
public interface InstagramClient {

    InstagramPost fetchPost(String shortcode, boolean reel, Duration timeout);

    InlineImage downloadImage(String mediaUrl, long maxBytes, Duration timeout);

    /** {@code target} 을 덮어쓰고 받은 byte 수를 돌려준다. */
    long downloadVideo(String mediaUrl, Path target, long maxBytes, Duration timeout);
}
