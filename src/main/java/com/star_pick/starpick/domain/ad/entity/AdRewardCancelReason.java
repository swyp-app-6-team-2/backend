package com.star_pick.starpick.domain.ad.entity;

/**
 * 사용자가 시청 세션을 포기하는 사유. REWARDED_AD_SSV.md §4.4.
 *
 * <p>포기는 실제 시청 여부에 대한 증거가 아니라 해당 세션의 보상 청구를 포기하는 요청이다.
 * enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 요청이 조용히 깨진다.
 */
public enum AdRewardCancelReason {
    /** 광고 로드 실패. */
    LOAD_FAILED,
    /** 사용자가 광고를 끝까지 보지 않고 닫았다. */
    USER_DISMISSED,
    /** 사용자가 명시적으로 시청을 포기했다. */
    USER_ABANDONED
}
