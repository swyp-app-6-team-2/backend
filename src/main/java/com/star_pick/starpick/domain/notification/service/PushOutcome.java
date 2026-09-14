package com.star_pick.starpick.domain.notification.service;

public enum PushOutcome {
    SENT,
    /** FCM 이 토큰 문자열 자체를 폐기했다고 판정. 토큰을 비활성화한다. */
    TOKEN_UNREGISTERED,
    FAILED
}
