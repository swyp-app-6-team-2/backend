package com.star_pick.starpick.domain.notification.repository;

import com.star_pick.starpick.domain.notification.domain.PushLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushLogRepository extends JpaRepository<PushLog, Long> {
}
