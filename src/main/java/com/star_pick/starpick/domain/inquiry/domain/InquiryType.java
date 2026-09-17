package com.star_pick.starpick.domain.inquiry.domain;

/**
 * 문의 유형.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 요청이 조용히 깨진다. API 는 상수명만 주고받고,
 * {@link #label()} 은 관리자 페이지와 운영 Discord 알림에서만 쓴다. 앱 화면과 같은 이름으로 맞춘다.
 */
public enum InquiryType {
    RECIPE("레시피"),
    SLOT("별 슬롯 확장"),
    ACCOUNT("계정·로그인"),
    NOTIFICATION("알림"),
    BUG("오류 신고"),
    ETC("제안·기타");

    private final String label;

    InquiryType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
