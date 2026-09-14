package com.star_pick.starpick.domain.notification.repository;

public record DueNotification(long pushLogId, long pushTokenId, String token, String label) {
}
