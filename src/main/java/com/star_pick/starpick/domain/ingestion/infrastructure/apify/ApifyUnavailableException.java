package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

/**
 * 보조 수집 호출 자체가 실패했다. 메시지에는 예외 클래스명과 HTTP 상태 코드만 담는다 —
 * 그대로 로그에 남으므로 URL·응답 본문·토큰은 넣지 않는다.
 */
public class ApifyUnavailableException extends RuntimeException {

    public ApifyUnavailableException(String reason) {
        super(reason);
    }
}
