package com.star_pick.starpick.domain.ad.service;

import com.star_pick.starpick.domain.ad.config.AdRewardProperties;
import com.star_pick.starpick.domain.ad.entity.AdRewardDailyQuota;
import com.star_pick.starpick.domain.ad.entity.AdRewardPlatform;
import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.ad.dto.request.AdRewardSessionCreateRequest;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResponse;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResultResponse;
import com.star_pick.starpick.domain.ad.entity.AdRewardCancelReason;
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
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 광고 시청 세션 발급·결과 조회·포기. REWARDED_AD_SSV.md §4.2, §4.3, §4.4, §7.
 *
 * <p>세션 상태를 바꾸는 모든 경로(발급의 지연 만료 정리, 포기)의 잠금 순서는
 * {@code users → daily_quota → session} 고정이다. 이 순서를 지키지 않으면 다른 지급·만료 경로와
 * 교착(deadlock)할 수 있다(§7). 결과 조회는 지급을 수행하지 않으므로 잠그지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdRewardSessionService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final UserRepository users;
    private final AdRewardDailyQuotaRepository quotas;
    private final AdRewardSessionRepository sessions;
    private final AdRewardProperties properties;
    private final AdRewardExpiryService expiryService;
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

        // quota 를 먼저 잠근다(users → daily_quota → session). 아래 진행 중 세션이 검증 마감을
        // 넘겼으면 여기서 바로 정리하는데, 그때 예약을 돌려줄 quota 가 이미 잠겨 있어야 한다.
        AdRewardDailyQuota quota = quotas.findForUpdate(userId, quotaDate)
                .orElseGet(() -> quotas.save(AdRewardDailyQuota.create(userId, quotaDate, now)));

        // 날짜별 사용자당 진행 중 세션 1개(§2.1). 전날 진행 중 세션은 여기 포함되지 않는다.
        Optional<AdRewardSession> pending = sessions
                .findByUserIdAndQuotaDateAndStatus(userId, quotaDate, AdRewardSessionStatus.PENDING)
                .stream().findFirst();
        if (pending.isPresent()) {
            AdRewardSession lockedPending = sessions.findByIdForUpdate(pending.get().getId()).orElseThrow();
            // 세션 발급 시 필요한 만료 정리(§2.1) — 검증 마감을 이미 넘긴 세션이면 새 발급을
            // 막는 대신 여기서 정리하고 계속 진행한다.
            if (!expiryService.expireIfDue(lockedPending, quota, now)) {
                throw new BusinessException(AdRewardErrorCode.AD_REWARD_SESSION_PENDING);
            }
        }

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

    /**
     * 시청 세션 포기. REWARDED_AD_SSV.md §4.4, §7.
     *
     * <p>{@code PENDING} 일 때만 실제로 취소하고 예약을 반환한다. 이미 확정된 세션(GRANTED 등)은
     * 취소·만료·거절 재요청과 마찬가지로 현재 결과를 그대로 반환한다 — 취소와 SSV 지급이
     * 경쟁하면 먼저 잠금을 얻어 커밋한 쪽의 결과를 따른다(같은 잠금 순서를 쓰므로 나중에 잠근
     * 쪽이 이미 바뀐 상태를 보고 멱등하게 끝난다).
     */
    @Transactional
    public AdRewardSessionResultResponse cancelSession(Long userId, UUID sessionId, AdRewardCancelReason reason) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        if (user.getDeletedAt() != null) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }

        AdRewardSession found = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(AdRewardErrorCode.AD_REWARD_SESSION_NOT_FOUND));

        if (found.getStatus() != AdRewardSessionStatus.PENDING) {
            return AdRewardSessionResultResponse.from(found, user);
        }

        AdRewardDailyQuota quota = quotas.findForUpdate(userId, found.getQuotaDate()).orElseThrow();
        AdRewardSession session = sessions.findByIdForUpdate(sessionId).orElseThrow();
        if (session.getStatus() != AdRewardSessionStatus.PENDING) {
            // 위 조회와 이 잠금 사이에 SSV 콜백이 먼저 확정했다. 그 결과를 그대로 따른다.
            return AdRewardSessionResultResponse.from(session, user);
        }

        session.markCancelled(reason.name(), clock.instant());
        quota.release();
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
