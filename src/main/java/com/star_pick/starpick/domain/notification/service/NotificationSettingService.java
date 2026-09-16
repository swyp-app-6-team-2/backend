package com.star_pick.starpick.domain.notification.service;

import com.star_pick.starpick.domain.user.service.UserLifecycleGuard;
import com.star_pick.starpick.domain.notification.controller.request.NotificationSettingRequest;
import com.star_pick.starpick.domain.notification.controller.response.NotificationSettingResponse;
import com.star_pick.starpick.domain.notification.domain.NotificationSetting;
import com.star_pick.starpick.domain.notification.domain.TimeSlot;
import com.star_pick.starpick.domain.notification.repository.NotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final NotificationSettingRepository repository;
    private final UserLifecycleGuard lifecycle;

    @Transactional(readOnly = true)
    public NotificationSettingResponse get(Long userId) {
        return repository.findByUserId(userId)
                .map(NotificationSettingResponse::from)
                .orElseGet(NotificationSettingResponse::empty);
    }

    /**
     * 배열·jsonb 를 native 파라미터로 넘기지 않고 Entity 매핑으로 저장한다. 행 잠금을 걸지 않는다 —
     * 전체 교체라 동시에 두 번 오면 나중 커밋이 이기면 되고, 병합할 것이 없다.
     */
    @Transactional
    public void save(Long userId, NotificationSettingRequest request) {
        lifecycle.lockActive(userId);
        repository.insertIfAbsent(userId);
        NotificationSetting setting = repository.findByUserId(userId).orElseThrow();
        setting.replace(
                request.enabled(),
                request.weekdays(),
                request.timeSlots().stream().map(slot -> TimeSlot.of(slot.label(), slot.time())).toList());
    }
}
