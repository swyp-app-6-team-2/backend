package com.star_pick.starpick.domain.ad.exception;

/**
 * AdMob SSV 서명 또는 콜백 구문이 유효하지 않다. REWARDED_AD_SSV.md §4.5 — 400, 지급 없음.
 *
 * <p>{@code BusinessException}/{@code ErrorCode} 를 쓰지 않는다. 콜백 응답은 공통 사용자용 JSON으로
 * 감싸지 않으므로 {@code GlobalExceptionHandler} 를 거치면 안 된다 — {@code AdRewardCallbackService}
 * 안에서만 잡혀 끝난다.
 */
public class AdRewardCallbackSignatureException extends RuntimeException {
    public AdRewardCallbackSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
