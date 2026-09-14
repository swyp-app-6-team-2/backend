package com.star_pick.starpick.domain.notification.service;

import com.star_pick.starpick.domain.notification.exception.NotificationErrorCode;
import com.star_pick.starpick.domain.notification.repository.PushLogRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationOpenService {

    private final PushLogRepository repository;

    public void open(Long userId, Long notificationId) {
        if (repository.markOpened(notificationId, userId, Instant.now()) == 0) {
            throw new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }
}
