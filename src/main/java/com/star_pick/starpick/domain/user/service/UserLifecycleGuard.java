package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 쓰기 트랜잭션의 첫 잠금. JDBC로 읽어 잠금 대기 전 JPA 캐시의 상태를 재사용하지 않는다. */
@Service
@RequiredArgsConstructor
public class UserLifecycleGuard {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActive(Long userId) {
        if (!lockIfActive(userId)) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockIfActive(Long userId) {
        return jdbc.query("select deleted_at from users where user_id = ? for update",
                (rs, row) -> rs.getTimestamp(1) == null, userId).stream().findFirst().orElse(false);
    }
}
