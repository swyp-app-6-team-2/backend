package com.star_pick.starpick.domain.ingestion.service;

import java.time.Duration;
import java.util.Optional;

/**
 * 공개 embed 가 영상을 주지 않은 Reel 의 보조 수집기. 외부 유료 API 를 쓴다.
 *
 * <p>실패는 예외가 아니라 빈 값이다. 보조 경로라서 실패하면 Worker 가 캡션 분석으로 내려간다.
 * 호출마다 외부 요청 한 번이고 재시도하지 않는다.
 */
public interface ReelVideoResolver {

    /**
     * @param url 정규화한 Instagram 링크({@code InstagramUrl#canonicalUrl})
     */
    Optional<ReelVideo> resolve(String url, Duration timeout);
}
