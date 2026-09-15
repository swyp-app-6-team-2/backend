package com.star_pick.starpick.domain.user.dto;

import com.star_pick.starpick.domain.user.entity.User;
import java.time.Instant;

public record OnboardingResponse(boolean onboardingRequired, Instant onboardingCompletedAt) {
    public static OnboardingResponse from(User user) {
        return new OnboardingResponse(user.isOnboardingRequired(), user.getOnboardingCompletedAt());
    }
}
