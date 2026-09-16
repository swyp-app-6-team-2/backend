package com.star_pick.starpick.domain.ad.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 탈퇴 오케스트레이터의 사용자 잠금·트랜잭션 안에서 실행한다. */
@Service
@RequiredArgsConstructor
public class AdRewardCleanupService {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllForUser(Long userId) {
        jdbc.update("update ad_reward_transaction set user_id = null, session_id = null where user_id = ? or session_id in (select id from ad_reward_session where user_id = ?)", userId, userId);
        jdbc.update("delete from ad_reward_session where user_id = ?", userId);
        jdbc.update("delete from ad_reward_daily_quota where user_id = ?", userId);
    }
}
