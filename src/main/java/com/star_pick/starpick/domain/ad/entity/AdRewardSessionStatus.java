package com.star_pick.starpick.domain.ad.entity;

/**
 * 시청 세션의 처리 상태. REWARDED_AD_SSV.md §4.3.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 분기가 조용히 깨진다.
 */
public enum AdRewardSessionStatus {
    /** 발급 직후. 횟수 1회를 예약한 상태다. */
    PENDING,
    /** SSV 검증을 통과해 슬롯이 지급됐다. */
    GRANTED,
    /** 사용자가 보상 청구를 포기했다. 이후 SSV 가 와도 자동 지급하지 않는다. */
    CANCELLED,
    /** 검증 수신 마감(verificationDeadline)까지 SSV 가 도착하지 않았다. */
    EXPIRED,
    /** 서명은 유효했으나 업무 검증에서 거절됐다. */
    REJECTED
}
