package com.star_pick.starpick.domain.ingestion.service;

import java.time.Duration;
import java.util.Optional;

/**
 * 공개 embed 가 영상을 주지 않은 Reel 의 보조 수집기. 외부 유료 API 를 쓴다.
 *
 * <p>못 찾으면 빈 값이고, 호출 자체가 실패하면 {@link RuntimeException} 을 던질 수 있다.
 * 호출자는 두 경우 모두 캡션 분석으로 내려간다 — 보조 경로라서 여기서 Job 을 실패시키지 않는다.
 * 호출마다 외부 요청 한 번이고 재시도하지 않는다.
 */
public interface ReelVideoResolver {

    /**
     * @param url 정규화한 Instagram 링크({@code InstagramUrl#canonicalUrl})
     */
    Optional<ReelVideo> resolve(String url, Duration timeout);
}
