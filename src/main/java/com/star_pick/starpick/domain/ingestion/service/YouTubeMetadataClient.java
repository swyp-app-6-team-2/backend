package com.star_pick.starpick.domain.ingestion.service;

import java.time.Duration;

public interface YouTubeMetadataClient {

    /**
     * 영상 설명란. 설명란이 없거나 비어 있으면 {@code null} 이다.
     *
     * <p>조회 실패는 삼키지 않고 예외로 올린다. 설명란 없이 분석을 계속할지는 호출자가 정한다
     * (CLAUDE.md: 최종 상태 전이와 실패 정책은 Adapter 가 아니라 Application/Worker 의 몫).
     */
    String description(String videoId, Duration timeout);
}
