package com.star_pick.starpick.domain.user.dto;

import com.star_pick.starpick.domain.user.entity.User;
import java.time.LocalDateTime;

public record OnboardingResponse(boolean onboardingRequired, LocalDateTime onboardingCompletedAt) {
    public static OnboardingResponse from(User user) {
        return new OnboardingResponse(user.isOnboardingRequired(), user.getOnboardingCompletedAt());
    }
}
