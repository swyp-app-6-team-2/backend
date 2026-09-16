package com.star_pick.starpick.domain.ad.entity;

/**
 * 광고 보상 상태 조회에서 시청이 불가능한 이유. REWARDED_AD_SSV.md §4.1.
 *
 * <p>UI 가 "오늘 횟수 모두 사용"과 "지급 검증 대기"를 다른 안내로 보여줄 수 있도록 구분한다.
 * DB 컬럼이 아니라 조회 시점에 계산하는 값이라 CHECK 제약과 무관하다.
 */
public enum AdRewardUnavailableReason {
    /** 지급·예약 횟수의 합이 일일 한도에 도달했다. */
    DAILY_LIMIT_REACHED,
    /** 당일 진행 중인 세션이 있어 검증 결과를 기다려야 한다. 잔여 횟수가 있어도 새로 시청할 수 없다. */
    REWARD_PENDING
}
