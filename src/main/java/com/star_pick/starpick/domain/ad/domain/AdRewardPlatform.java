package com.star_pick.starpick.domain.ad.domain;

/**
 * 시청 세션을 발급한 클라이언트 플랫폼.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 요청이 조용히 깨진다. 플랫폼별 광고 단위는
 * {@code AdRewardProperties} 설정으로 선택한다.
 */
public enum AdRewardPlatform {
    ANDROID,
    IOS
}
