package com.star_pick.starpick.domain.ingestion.service;

import java.time.Duration;
import java.util.Optional;

/**
 * 공개 embed 가 영상을 주지 않은 Reel 의 보조 수집기. 외부 유료 API 를 쓴다.
 *
 * <p>못 찾으면 빈 값이고, 호출 자체가 실패하면 {@link RuntimeException} 을 던질 수 있다.
 * 호출자는 두 경우 모두 캡션 분석으로 내려간다 — 보조 경로라서 여기서 Job 을 실패시키지 않는다.
 * 호출마다 외부 요청 한 번이고 재시도하지 않는다.
 *
 * <p><b>예외 메시지는 그대로 로그에 남는다.</b> 구현은 예외 클래스명과 HTTP 상태 코드까지만 담고
 * URL·캡션·응답 본문·토큰은 담지 않는다.
 */
public interface ReelVideoResolver {

    /**
     * @param shortcode 요청한 Reel 의 코드. 받은 항목이 이 Reel 이 아니면 빈 값이어야 한다 —
     *                  외부 API 가 같은 계정의 다른 영상을 돌려줄 수 있다.
     * @param url       정규화한 Instagram 링크({@code InstagramUrl#canonicalUrl})
     */
    Optional<ReelVideo> resolve(String shortcode, String url, Duration timeout);
}
