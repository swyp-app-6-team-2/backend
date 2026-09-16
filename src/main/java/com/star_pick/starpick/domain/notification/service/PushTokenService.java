package com.star_pick.starpick.domain.notification.service;

import com.star_pick.starpick.domain.user.service.UserLifecycleGuard;
import com.star_pick.starpick.domain.notification.domain.PushPlatform;
import com.star_pick.starpick.domain.notification.repository.PushTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PushTokenService {

    private final PushTokenRepository repository;
    private final UserLifecycleGuard lifecycle;

    @org.springframework.transaction.annotation.Transactional
    public void register(Long userId, String token, PushPlatform platform) {
        lifecycle.lockActive(userId);
        repository.upsert(userId, token, platform.name());
    }

    /** 결과와 무관하게 성공이다. 남의 토큰·없는 토큰이어도 같은 응답이라 소유 여부가 드러나지 않는다. */
    @org.springframework.transaction.annotation.Transactional
    public void unregister(Long userId, String token) {
        lifecycle.lockActive(userId);
        repository.deactivate(userId, token);
    }
}
