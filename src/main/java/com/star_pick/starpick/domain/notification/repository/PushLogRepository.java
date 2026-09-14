package com.star_pick.starpick.domain.notification.repository;

import com.star_pick.starpick.domain.notification.domain.PushLog;
import com.star_pick.starpick.domain.notification.domain.PushStatus;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PushLogRepository extends JpaRepository<PushLog, Long> {

    @Transactional
    @Modifying
    @Query("update PushLog p set p.status = :status where p.id in :ids")
    int updateStatus(@Param("ids") Collection<Long> ids, @Param("status") PushStatus status);

    /** 이미 열었으면 첫 시각을 유지한다. 발송 상태는 보지 않는다. */
    @Transactional
    @Modifying
    @Query("update PushLog p set p.openedAt = coalesce(p.openedAt, :now) where p.id = :id and p.userId = :userId")
    int markOpened(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);
}
