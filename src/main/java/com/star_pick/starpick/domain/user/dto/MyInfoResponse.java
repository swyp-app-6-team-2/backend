package com.star_pick.starpick.domain.user.dto;

import com.star_pick.starpick.domain.user.entity.Profile;
import com.star_pick.starpick.domain.user.entity.User;

public record MyInfoResponse(
        Long userId,
        String nickname,
        String profileImageUrl,
        int remainingRecipeSlots,
        int recipeSlotLimit,
        int cumulativeRecipeCount
) {
    public static MyInfoResponse from(User user, Profile profile) {
        return new MyInfoResponse(
                user.getUserId(),
                profile != null ? profile.getNickname() : null,
                profile != null ? profile.getProfileImageUrl() : null,
                user.getRemainingRecipeSlots(),
                user.getRecipeSlotLimit(),
                user.getCumulativeRecipeCount()
        );
    }
}