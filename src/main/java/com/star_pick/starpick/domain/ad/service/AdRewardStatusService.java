package com.star_pick.starpick.domain.ad.service;

import com.star_pick.starpick.domain.ad.config.AdRewardProperties;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardStatusResponse;
import com.star_pick.starpick.domain.ad.entity.AdRewardDailyQuota;
import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.ad.entity.AdRewardUnavailableReason;
import com.star_pick.starpick.domain.ad.repository.AdRewardDailyQuotaRepository;
import com.star_pick.starpick.domain.ad.repository.AdRewardSessionRepository;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 광고 보상 상태 조회. REWARDED_AD_SSV.md §4.1.
 *
 * <p>슬롯을 지급하지 않는 읽기 전용 조회다. {@code REPEATABLE_READ} 로 여는 이유는 이 메서드가
 * daily_quota 와 session 을 각각 다른 SELECT 로 읽기 때문이다 — 기본 READ COMMITTED 라면 두 SELECT
 * 사이에 동시 지급이 커밋되어 서로 모순된 카운터 조합(예: dailyRewardCount 는 지급 후, reservedCount
 * 는 지급 전)을 응답할 수 있다. REPEATABLE_READ 는 트랜잭션 시작 시점의 스냅샷을 트랜잭션 내내
 * 유지해 이 문제를 막는다.
 */
@Service
@RequiredArgsConstructor
public class AdRewardStatusService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final UserRepository users;
    private final AdRewardDailyQuotaRepository quotas;
    private final AdRewardSessionRepository sessions;
    private final AdRewardProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdRewardStatusResponse getStatus(Long userId) {
        User user = users.findById(userId).orElseThrow(this::unauthorized);
        if (user.getDeletedAt() != null) {
            throw unauthorized();
        }

        LocalDate quotaDate = LocalDate.now(clock.withZone(SEOUL));
        AdRewardDailyQuota quota = quotas.findByUserIdAndQuotaDate(userId, quotaDate).orElse(null);
        int dailyRewardCount = quota != null ? quota.getGrantedCount() : 0;
        int reservedCount = quota != null ? quota.getReservedCount() : 0;
        int dailyRewardLimit = properties.dailyLimit();
        int remainingRewardCount = dailyRewardLimit - dailyRewardCount;
        int availableWatchCount = dailyRewardLimit - dailyRewardCount - reservedCount;

        // 전날의 미완료 세션도 복구할 수 있도록 날짜로 좁히지 않는다. 다만 당일 카운터에는
        // 영향을 주지 않는다 — daily_quota 조회가 이미 quotaDate 로 좁혀 있기 때문이다.
        var pendingSessions = sessions.findByUserIdAndStatus(userId, AdRewardSessionStatus.PENDING);
        boolean hasTodayPendingSession = pendingSessions.stream()
                .anyMatch(session -> session.getQuotaDate().equals(quotaDate));

        AdRewardUnavailableReason unavailableReason;
        if (hasTodayPendingSession) {
            unavailableReason = AdRewardUnavailableReason.REWARD_PENDING;
        } else if (availableWatchCount <= 0) {
            unavailableReason = AdRewardUnavailableReason.DAILY_LIMIT_REACHED;
        } else {
            unavailableReason = null;
        }
        boolean canWatchAd = unavailableReason == null;

        Instant resetsAt = ZonedDateTime.of(quotaDate.plusDays(1), LocalTime.MIDNIGHT, SEOUL).toInstant();

        return new AdRewardStatusResponse(
                user.getRecipeSlotLimit(),
                user.getRemainingRecipeSlots(),
                dailyRewardCount,
                dailyRewardLimit,
                reservedCount,
                remainingRewardCount,
                availableWatchCount,
                canWatchAd,
                unavailableReason,
                quotaDate,
                resetsAt,
                pendingSessions.stream().map(AdRewardStatusResponse.PendingSession::from).toList());
    }

    private BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
