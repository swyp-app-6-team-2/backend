package com.star_pick.starpick.domain.ad.dto.response;

import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** REWARDED_AD_SSV.md §4.2 응답 계약. */
public record AdRewardSessionResponse(
        UUID sessionId,
        AdRewardSessionStatus status,
        String adUnitId,
        String customData,
        String rewardType,
        int rewardAmount,
        LocalDate quotaDate,
        Instant expiresAt,
        Instant verificationDeadline
) {
    /**
     * @param adUnitId 앱이 광고를 로드할 때 쓰는 값. Entity 에는 저장하지 않는다 — 저장되는 값은
     *                 콜백 대조용 {@code expectedAdUnit} 뿐이다(§5.6).
     */
    public static AdRewardSessionResponse from(AdRewardSession session, String adUnitId) {
        return new AdRewardSessionResponse(
                session.getId(),
                session.getStatus(),
                adUnitId,
                session.getId().toString(),
                session.getRewardType(),
                session.getRewardAmount(),
                session.getQuotaDate(),
                session.getExpiresAt(),
                session.getVerificationDeadline());
    }
}
