package com.star_pick.starpick.domain.notification.repository;

import com.star_pick.starpick.domain.notification.domain.PushLog;
import com.star_pick.starpick.domain.notification.domain.PushStatus;
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
}
