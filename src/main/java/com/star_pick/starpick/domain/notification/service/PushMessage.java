package com.star_pick.starpick.domain.notification.service;

public record PushMessage(long pushLogId, long pushTokenId, String token, String title, String body, String deepLink) {
}
