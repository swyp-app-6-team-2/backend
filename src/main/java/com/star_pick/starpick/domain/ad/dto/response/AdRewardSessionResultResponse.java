package com.star_pick.starpick.domain.ad.dto.response;

import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.user.entity.User;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** REWARDED_AD_SSV.md §4.3 응답 계약. */
public record AdRewardSessionResultResponse(
        UUID sessionId,
        AdRewardSessionStatus status,
        String reasonCode,
        LocalDate quotaDate,
        int grantedAmount,
        Instant grantedAt,
        int recipeSlotLimit,
        int remainingRecipeSlots
) {
    /** {@code recipeSlotLimit}/{@code remainingRecipeSlots} 는 조회 시점의 최신 값이다 — 지급 당시 값을 저장해 두지 않는다. */
    public static AdRewardSessionResultResponse from(AdRewardSession session, User user) {
        boolean granted = session.getStatus() == AdRewardSessionStatus.GRANTED;
        return new AdRewardSessionResultResponse(
                session.getId(),
                session.getStatus(),
                session.getReasonCode(),
                session.getQuotaDate(),
                granted ? session.getRewardAmount() : 0,
                session.getGrantedAt(),
                user.getRecipeSlotLimit(),
                user.getRemainingRecipeSlots());
    }
}
