package com.star_pick.starpick.domain.notification.service;

/** {@code errorCode} 는 FCM 오류 코드 이름이다. 성공이면 null. 로그 집계에만 쓴다. */
public record PushSendResult(long pushLogId, long pushTokenId, PushOutcome outcome, String errorCode) {
}
