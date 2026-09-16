package com.star_pick.starpick.domain.ad.dto.response;

import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.ad.entity.AdRewardUnavailableReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** REWARDED_AD_SSV.md §4.1 응답 계약. */
public record AdRewardStatusResponse(
        int recipeSlotLimit,
        int remainingRecipeSlots,
        int dailyRewardCount,
        int dailyRewardLimit,
        int reservedCount,
        int remainingRewardCount,
        int availableWatchCount,
        boolean canWatchAd,
        AdRewardUnavailableReason unavailableReason,
        LocalDate quotaDate,
        Instant resetsAt,
        List<PendingSession> pendingSessions
) {
    /**
     * 진행 중(PENDING) 세션. 당일 것뿐 아니라 전날의 미완료 세션도 포함해 앱이 복구할 수 있게
     * 한다 — 단, 당일 카운터(dailyRewardCount 등)에는 당일 세션만 반영된다(§4.1).
     */
    public record PendingSession(
            UUID sessionId,
            AdRewardSessionStatus status,
            LocalDate quotaDate,
            Instant expiresAt,
            Instant verificationDeadline
    ) {
        public static PendingSession from(AdRewardSession session) {
            return new PendingSession(session.getId(), session.getStatus(), session.getQuotaDate(),
                    session.getExpiresAt(), session.getVerificationDeadline());
        }
    }
}
