package com.star_pick.starpick.domain.notification.repository;

import com.star_pick.starpick.domain.notification.domain.PushToken;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    /** 새 토큰, 같은 사용자의 재등록, 다른 계정으로 넘어간 토큰을 한 문장으로 처리한다. */
    @Transactional
    @Modifying
    @Query(value = """
            insert into push_token (user_id, token, platform, active)
            values (:userId, :token, :platform, true)
            on conflict (token) do update
                set user_id = excluded.user_id, platform = excluded.platform, active = true
            """, nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("token") String token, @Param("platform") String platform);

    @Transactional
    @Modifying
    @Query("update PushToken t set t.active = false where t.token = :token and t.userId = :userId")
    int deactivate(@Param("userId") Long userId, @Param("token") String token);

    @Transactional
    @Modifying
    @Query("update PushToken t set t.active = false where t.id in :ids")
    int deactivateAll(@Param("ids") Collection<Long> ids);
}
