package com.star_pick.starpick.domain.ad.service;

import com.star_pick.starpick.domain.ad.config.AdRewardProperties;
import com.star_pick.starpick.domain.ad.entity.AdRewardDailyQuota;
import com.star_pick.starpick.domain.ad.entity.AdRewardPlatform;
import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.ad.dto.request.AdRewardSessionCreateRequest;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResponse;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResultResponse;
import com.star_pick.starpick.domain.ad.exception.AdRewardErrorCode;
import com.star_pick.starpick.domain.ad.repository.AdRewardDailyQuotaRepository;
import com.star_pick.starpick.domain.ad.repository.AdRewardSessionRepository;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 광고 시청 세션 발급과 결과 조회. REWARDED_AD_SSV.md §4.2, §4.3, §7.
 *
 * <p>발급의 잠금 순서는 {@code users → daily_quota → session} 고정이다. 이 순서를 지키지 않으면
 * 다른 지급·취소·만료 경로와 교착(deadlock)할 수 있다(§7). 결과 조회는 지급을 수행하지 않으므로
 * 잠그지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdRewardSessionService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final UserRepository users;
    private final AdRewardDailyQuotaRepository quotas;
    private final AdRewardSessionRepository sessions;
    private final AdRewardProperties properties;
    private final Clock clock;

    @Transactional
    public AdRewardSessionResponse createSession(Long userId, AdRewardSessionCreateRequest request) {
        lockActiveUser(userId);

        var existing = sessions.findByUserIdAndRequestId(userId, request.requestId());
        if (existing.isPresent()) {
            AdRewardSession found = existing.get();
            if (found.getPlatform() != request.platform()) {
                throw new BusinessException(AdRewardErrorCode.AD_REWARD_REQUEST_ID_CONFLICT);
            }
            return toResponse(found);
        }

        AdRewardProperties.Platform platformConfig = platformConfig(request.platform());
        if (platformConfig.adUnitId() == null || platformConfig.adUnitId().isBlank()) {
            throw new BusinessException(AdRewardErrorCode.AD_REWARD_PLATFORM_UNAVAILABLE);
        }

        Instant now = clock.instant();
        LocalDate quotaDate = ZonedDateTime.ofInstant(now, SEOUL).toLocalDate();

        // 날짜별 사용자당 진행 중 세션 1개(§2.1). 전날 진행 중 세션은 여기 포함되지 않는다.
        if (!sessions.findByUserIdAndQuotaDateAndStatus(userId, quotaDate, AdRewardSessionStatus.PENDING).isEmpty()) {
            throw new BusinessException(AdRewardErrorCode.AD_REWARD_SESSION_PENDING);
        }

        AdRewardDailyQuota quota = quotas.findForUpdate(userId, quotaDate)
                .orElseGet(() -> quotas.save(AdRewardDailyQuota.create(userId, quotaDate, now)));
        if (quota.getGrantedCount() + quota.getReservedCount() >= properties.dailyLimit()) {
            throw new BusinessException(AdRewardErrorCode.AD_REWARD_DAILY_LIMIT_REACHED);
        }
        quota.reserve();

        AdRewardSession session = AdRewardSession.issue(userId, request.requestId(), request.platform(),
                platformConfig.expectedCallbackAdUnit(), properties.rewardType(), properties.rewardAmount(),
                quotaDate, now, now.plus(properties.sessionValidity()), now.plus(properties.verificationDeadline()));
        sessions.save(session);

        return toResponse(session);
    }

    /**
     * 세션 처리 결과 조회. REWARDED_AD_SSV.md §4.3.
     *
     * <p>잠그지 않는다 — GET 은 지급을 수행하지 않고, 반복 조회로 상태가 바뀌면 안 된다. 없거나
     * 다른 사용자의 세션이면 동일한 404 다(소유 여부를 노출하지 않는다).
     */
    @Transactional(readOnly = true)
    public AdRewardSessionResultResponse getSessionResult(Long userId, UUID sessionId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        if (user.getDeletedAt() != null) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
        AdRewardSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(AdRewardErrorCode.AD_REWARD_SESSION_NOT_FOUND));
        return AdRewardSessionResultResponse.from(session, user);
    }

    private void lockActiveUser(Long userId) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        if (user.getDeletedAt() != null) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private AdRewardProperties.Platform platformConfig(AdRewardPlatform platform) {
        return switch (platform) {
            case ANDROID -> properties.android();
            case IOS -> properties.ios();
        };
    }

    private AdRewardSessionResponse toResponse(AdRewardSession session) {
        return AdRewardSessionResponse.from(session, platformConfig(session.getPlatform()).adUnitId());
    }
}
