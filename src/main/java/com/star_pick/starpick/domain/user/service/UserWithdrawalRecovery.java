package com.star_pick.starpick.domain.user.service;

import java.time.Clock;
import java.time.Duration;
import java.sql.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 커서는 효율을 위한 값일 뿐이다. 복구 대상은 DB deleted_at으로 재발견한다. */
@Slf4j
@Service
public class UserWithdrawalRecovery {
    private final JdbcTemplate jdbc;
    private final UserWithdrawalService withdrawals;
    private final Clock clock;
    private final int batchSize;
    private long cursor;

    public UserWithdrawalRecovery(JdbcTemplate jdbc, UserWithdrawalService withdrawals, Clock clock,
            @Value("${user-withdrawal.recovery.batch-size:20}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("복구 배치 크기는 1~1000입니다.");
        this.jdbc = jdbc;
        this.withdrawals = withdrawals;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public synchronized void recoverBatch() {
        var ids = jdbc.queryForList("""
                select user_id from users where deleted_at is not null and user_id > ?
                order by user_id limit ?
                """, Long.class, cursor, batchSize);
        if (ids.isEmpty()) { cursor = 0; return; }
        for (Long id : ids) {
            cursor = id;
            try { withdrawals.resume(id); }
            catch (RuntimeException e) { log.error("회원 탈퇴 자동 복구 실패. userId={}", id, e); }
        }
        Long overdue = jdbc.queryForObject("select count(*) from users where deleted_at < ?", Long.class,
                Timestamp.from(clock.instant().minus(Duration.ofHours(1))));
        if (overdue != null && overdue > 0) log.warn("1시간 이상 지연된 회원 탈퇴. count={}", overdue);
    }
}
