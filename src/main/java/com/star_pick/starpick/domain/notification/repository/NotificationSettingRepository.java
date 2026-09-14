package com.star_pick.starpick.domain.notification.repository;

import com.star_pick.starpick.domain.notification.domain.NotificationSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    Optional<NotificationSetting> findByUserId(Long userId);

    /** 첫 저장이 동시에 두 번 와도 UNIQUE 위반 500 없이 row 하나만 만든다. 값은 Entity 로 채운다. */
    @Modifying
    @Query(value = """
            insert into notification_setting (user_id, enabled) values (:userId, false)
            on conflict (user_id) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("userId") Long userId);
}
