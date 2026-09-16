package com.star_pick.starpick.domain.ad.service;

/**
 * SSV 콜백 처리 결과. 컨트롤러가 이 값만 보고 HTTP status 를 정한다. REWARDED_AD_SSV.md §4.5.
 *
 * <p>{@code PROCESSED} 는 지급 성공을 뜻하지 않는다 — 콜백 처리가 끝났다는 뜻이다(거절·중복
 * 포함). 앱은 세션 상태를 따로 조회한다.
 */
public enum AdRewardCallbackOutcome {
    /** 처리 완료. 지급·거절·이미 처리한 거래 모두 포함한다 — 200. */
    PROCESSED,
    /** 서명 불일치 또는 콜백 구문 오류. 지급 없음 — 400. */
    INVALID,
    /** 공개키 조회 등 일시 장애로 판단을 완료하지 못했다. 성공 처리로 삼지 않는다 — 5xx. */
    UNAVAILABLE
}
