package com.star_pick.starpick.domain.inquiry.domain;

/**
 * 문의 유형.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 요청이 조용히 깨진다. 표시 이름은 앱이 보유한다.
 */
public enum InquiryType {
    RECIPE,
    SLOT,
    ACCOUNT,
    NOTIFICATION,
    BUG,
    ETC
}
