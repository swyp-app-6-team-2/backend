package com.star_pick.starpick.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 탈퇴 오케스트레이터의 사용자 잠금·트랜잭션 안에서 실행한다. */
@Service
@RequiredArgsConstructor
public class NotificationCleanupService {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllForUser(Long userId) {
        jdbc.update("delete from push_log where user_id = ?", userId);
        jdbc.update("delete from push_token where user_id = ?", userId);
        jdbc.update("delete from notification_setting where user_id = ?", userId);
    }
}
