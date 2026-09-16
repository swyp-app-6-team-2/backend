package com.star_pick.starpick.domain.ad.exception;

/**
 * 공개키 조회 등 일시 장애로 서명 검증 자체를 완료하지 못했다. REWARDED_AD_SSV.md §4.5 — 5xx.
 *
 * <p>서명 불일치와 같은 오류로 취급하지 않는다(§5) — 이 예외는 지급 거부가 아니라 "판단 불가"이므로
 * 성공 처리로 삼지 않고 Google 의 재시도를 기대한다.
 */
public class AdRewardCallbackVerifierUnavailableException extends RuntimeException {
    public AdRewardCallbackVerifierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
