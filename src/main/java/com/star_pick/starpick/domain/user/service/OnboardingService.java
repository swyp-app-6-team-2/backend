package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.domain.user.dto.OnboardingResponse;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {
    private final UserRepository users;

    @Transactional(readOnly = true)
    public OnboardingResponse status(Long userId) {
        return OnboardingResponse.from(active(users.findById(userId).orElseThrow(this::unauthorized)));
    }

    @Transactional
    public OnboardingResponse complete(Long userId) {
        // 반복 및 동시 요청에서도 최초 완료 시각을 보존한다.
        User user = active(users.findByIdForUpdate(userId).orElseThrow(this::unauthorized));
        user.completeOnboarding(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return OnboardingResponse.from(user);
    }

    private User active(User user) {
        if (user.getDeletedAt() != null) throw unauthorized();
        return user;
    }

    private BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
