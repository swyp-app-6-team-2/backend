package com.star_pick.starpick.domain.ad.entity;

/**
 * Google SSV 콜백 한 건의 처리 결과. REWARDED_AD_SSV.md §6.
 *
 * <p>서명은 유효하지만 업무 검증에서 거절한 거래도 {@code REJECTED} 로 기록해 다음 날 재전송으로
 * 지급되는 것을 막는다(§6). enum 상수명이 곧 공개 API 계약이다.
 */
public enum AdRewardTransactionStatus {
    GRANTED,
    REJECTED
}
