package com.star_pick.starpick.domain.ad.service;

import com.star_pick.starpick.domain.ad.config.AdRewardProperties;
import com.star_pick.starpick.domain.ad.entity.AdRewardDailyQuota;
import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.ad.entity.AdRewardTransaction;
import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackSignatureException;
import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackVerifierUnavailableException;
import com.star_pick.starpick.domain.ad.repository.AdRewardDailyQuotaRepository;
import com.star_pick.starpick.domain.ad.repository.AdRewardSessionRepository;
import com.star_pick.starpick.domain.ad.repository.AdRewardTransactionRepository;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.domain.user.service.UserRecipeStatsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Google AdMob SSV 콜백 검증·지급. REWARDED_AD_SSV.md §5, §6, §7.
 *
 * <p>서명 검증(외부 공개키 조회 포함)은 DB 트랜잭션 밖에서 끝낸다. 지급 여부를 정하고 커밋하는
 * 구간만 {@link TransactionTemplate} 으로 연다 — 같은 객체 내부 호출로는 {@code @Transactional} 이
 * Spring 프록시를 타지 않으므로 self-invocation 에 기대지 않는다(§5).
 *
 * <p>잠금 순서는 {@code users → daily_quota → session} 고정이다(§7). {@code daily_quota} 의 키가
 * {@code (userId, quotaDate)} 라 세션을 먼저 봐야 quotaDate 를 알 수 있는데, 그렇다고 세션을 먼저
 * 잠그면 순서가 깨진다. 그래서 잠금 없는 사전 조회로 {@code userId}·{@code quotaDate} 만 미리 얻고
 * (변경되지 않는 컬럼이라 안전하다), 트랜잭션 안에서는 배운 값으로 사용자 → 일일 상태 → 세션
 * 순서로 다시 잠근다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdRewardCallbackService {

    private static final String REASON_SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    private static final String REASON_VERIFICATION_DEADLINE_PASSED = "VERIFICATION_DEADLINE_PASSED";
    private static final String REASON_AD_UNIT_MISMATCH = "AD_UNIT_MISMATCH";
    private static final String REASON_REWARD_ITEM_MISMATCH = "REWARD_ITEM_MISMATCH";
    private static final String REASON_REWARD_AMOUNT_MISMATCH = "REWARD_AMOUNT_MISMATCH";
    private static final String REASON_EVENT_TIME_INVALID = "EVENT_TIME_INVALID";
    private static final String REASON_ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE";
    private static final String REASON_DAILY_LIMIT_REACHED = "DAILY_LIMIT_REACHED";

    private final AdRewardCallbackVerifier verifier;
    private final UserRepository users;
    private final AdRewardSessionRepository sessions;
    private final AdRewardDailyQuotaRepository quotas;
    private final AdRewardTransactionRepository transactions;
    private final AdRewardProperties properties;
    private final UserRecipeStatsService userRecipeStatsService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AdRewardCallbackOutcome processCallback(String rawQueryString, Map<String, String[]> queryParams) {
        Instant receivedAt = clock.instant();

        if (rawQueryString == null || rawQueryString.isBlank()) {
            log.warn("AdMob SSV 콜백에 query string이 없다.");
            return AdRewardCallbackOutcome.INVALID;
        }
        try {
            verifier.verify(rawQueryString);
        } catch (AdRewardCallbackVerifierUnavailableException e) {
            log.error("AdMob SSV 공개키 조회 실패. 성공 처리로 취급하지 않는다.", e);
            return AdRewardCallbackOutcome.UNAVAILABLE;
        } catch (AdRewardCallbackSignatureException e) {
            log.warn("AdMob SSV 서명 검증 실패: {}", e.getMessage());
            return AdRewardCallbackOutcome.INVALID;
        }

        AdRewardCallbackParams parsed;
        try {
            parsed = AdRewardCallbackParams.parse(queryParams);
        } catch (IllegalArgumentException e) {
            log.warn("AdMob SSV 콜백 파라미터 오류: {}", e.getMessage());
            return AdRewardCallbackOutcome.INVALID;
        }

        // 빠른 경로일 뿐 최종 방어는 아니다(§7). 최종 방어는 저장 시 UNIQUE(transaction_id) 다.
        if (transactions.existsByTransactionId(parsed.transactionId())) {
            return AdRewardCallbackOutcome.PROCESSED;
        }

        // 잠금 없는 사전 조회. userId·quotaDate 는 바뀌지 않는 컬럼이라 트랜잭션 안에서 다시
        // 잠글 때 이 값을 그대로 써도 안전하다 — 잠금 순서(users → daily_quota → session)를
        // 지키려면 세션 자체보다 사용자를 먼저 잠가야 하는데, quotaDate 를 모르면 daily_quota 를
        // 찾을 수 없어서다.
        var sessionLookup = sessions.findById(parsed.sessionId());

        try {
            if (sessionLookup.isEmpty()) {
                transactionTemplate.executeWithoutResult(status -> recordRejectedSafely(
                        parsed, null, null, receivedAt, clock.instant(), REASON_SESSION_NOT_FOUND));
            } else {
                Long userId = sessionLookup.get().getUserId();
                LocalDate quotaDate = sessionLookup.get().getQuotaDate();
                transactionTemplate.executeWithoutResult(status -> process(userId, quotaDate, parsed, receivedAt));
            }
        } catch (DataIntegrityViolationException e) {
            if (!transactions.existsByTransactionId(parsed.transactionId())) {
                log.error("거래 저장 실패가 중복 때문이 아니다: transactionId={}", parsed.transactionId(), e);
                return AdRewardCallbackOutcome.UNAVAILABLE;
            }
        }
        return AdRewardCallbackOutcome.PROCESSED;
    }

    private void process(Long userId, LocalDate quotaDate, AdRewardCallbackParams parsed, Instant receivedAt) {
        Instant now = clock.instant();

        User user = users.findByIdForUpdate(userId).orElse(null);
        AdRewardDailyQuota quota = quotas.findForUpdate(userId, quotaDate).orElse(null);
        AdRewardSession session = sessions.findByIdForUpdate(parsed.sessionId()).orElse(null);

        if (session == null) {
            // 세션 정리와 최종 사용자 삭제 사이의 콜백도 사용자 FK를 다시 만들지 않는다.
            Long remainingUserId = user == null || user.getDeletedAt() != null ? null : userId;
            recordRejectedSafely(parsed, null, remainingUserId, receivedAt, now, REASON_SESSION_NOT_FOUND);
            return;
        }

        String rejectionReason = validate(session, quota, user, parsed, now);
        if (rejectionReason != null) {
            if (rejectionReason.equals(REASON_VERIFICATION_DEADLINE_PASSED)
                    && session.getStatus() == AdRewardSessionStatus.PENDING) {
                session.markExpired(rejectionReason);
                if (quota != null) {
                    quota.release();
                }
            }
            recordRejectedSafely(parsed, session.getId(), userId, receivedAt, now, rejectionReason);
            return;
        }

        quota.grant();
        session.markGranted(now);
        userRecipeStatsService.onAdRewardGranted(userId, session.getRewardAmount());
        transactions.save(AdRewardTransaction.granted(parsed.transactionId(), session.getId(), userId,
                parsed.adUnit(), parsed.rewardItem(), parsed.rewardAmount(), parsed.eventTime(), receivedAt, now,
                session.getRewardAmount(), now));
    }

    /** null 이면 지급 가능하다는 뜻이다. 아니면 거절 사유 코드다. */
    private String validate(AdRewardSession session, AdRewardDailyQuota quota, User user,
            AdRewardCallbackParams parsed, Instant now) {
        if (session.getStatus() != AdRewardSessionStatus.PENDING) {
            return "SESSION_ALREADY_" + session.getStatus();
        }
        if (now.isAfter(session.getVerificationDeadline())) {
            return REASON_VERIFICATION_DEADLINE_PASSED;
        }
        if (!session.getExpectedAdUnit().equals(parsed.adUnit())) {
            return REASON_AD_UNIT_MISMATCH;
        }
        if (!properties.rewardType().equals(parsed.rewardItem())) {
            return REASON_REWARD_ITEM_MISMATCH;
        }
        if (session.getRewardAmount() != parsed.rewardAmount()) {
            return REASON_REWARD_AMOUNT_MISMATCH;
        }
        Instant earliestValid = session.getCreatedAt().minus(properties.clockSkewTolerance());
        Instant latestValid = session.getExpiresAt();
        if (parsed.eventTime().isBefore(earliestValid) || parsed.eventTime().isAfter(latestValid)) {
            return REASON_EVENT_TIME_INVALID;
        }
        if (parsed.eventTime().isAfter(now.plus(properties.clockSkewTolerance()))) {
            return REASON_EVENT_TIME_INVALID;
        }
        if (user == null || user.getDeletedAt() != null) {
            return REASON_ACCOUNT_INACTIVE;
        }
        if (quota == null || quota.getGrantedCount() >= properties.dailyLimit()) {
            return REASON_DAILY_LIMIT_REACHED;
        }
        return null;
    }

    private void recordRejectedSafely(AdRewardCallbackParams parsed, UUID sessionId, Long userId,
            Instant receivedAt, Instant processedAt, String reasonCode) {
        transactions.save(AdRewardTransaction.rejected(parsed.transactionId(), sessionId, userId,
                parsed.adUnit(), parsed.rewardItem(), parsed.rewardAmount(), parsed.eventTime(), receivedAt,
                processedAt, reasonCode));
    }
}
