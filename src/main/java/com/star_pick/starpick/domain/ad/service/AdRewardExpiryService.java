package com.star_pick.starpick.domain.ad.service;

import com.star_pick.starpick.domain.ad.entity.AdRewardDailyQuota;
import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.ad.repository.AdRewardDailyQuotaRepository;
import com.star_pick.starpick.domain.ad.repository.AdRewardSessionRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검증 수신 마감을 넘긴 세션의 만료 전환. REWARDED_AD_SSV.md §2.1, §6, §7.
 *
 * <p>두 호출 경로가 이 로직을 공유한다: (1) 세션 발급이 당일 진행 중 세션을 만났을 때 지연 정리로
 * 부르고(§2.1 "세션 발급 시 필요한 만료 정리"), (2) {@code AdRewardMaintenance} 가 주기 작업으로
 * 전체를 훑을 때 {@link #expireOne} 을 부른다. {@code expireOne} 이 별도 Bean 메서드인 이유는
 * Spring 프록시를 거치게 하기 위해서다 — 같은 객체 내부 호출로는 {@code @Transactional} 이 걸리지
 * 않는다(§5).
 */
@Service
@RequiredArgsConstructor
public class AdRewardExpiryService {

    private static final String REASON_VERIFICATION_DEADLINE_PASSED = "VERIFICATION_DEADLINE_PASSED";

    private final AdRewardSessionRepository sessions;
    private final AdRewardDailyQuotaRepository quotas;

    /**
     * 주기 작업이 후보 id 하나를 건별로 처리할 때 쓴다. 자체 트랜잭션을 열어 잠그므로 호출자가
     * 미리 잠글 필요가 없다.
     *
     * @return 실제로 만료시켰으면 true. 이미 다른 경로가 먼저 처리했으면 false(중복 카운트 방지).
     */
    @Transactional
    public boolean expireOne(UUID sessionId, Instant now) {
        AdRewardSession session = sessions.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        AdRewardDailyQuota quota = quotas.findForUpdate(session.getUserId(), session.getQuotaDate()).orElse(null);
        return expireIfDue(session, quota, now);
    }

    /**
     * 세션 발급처럼 호출자가 이미 {@code users → daily_quota → session} 순서로 잠근 상태에서 쓴다.
     * 이 메서드는 스스로 잠그거나 트랜잭션을 열지 않는다 — 호출자의 트랜잭션에 참여한다.
     */
    public boolean expireIfDue(AdRewardSession session, AdRewardDailyQuota quota, Instant now) {
        if (session.getStatus() != AdRewardSessionStatus.PENDING) {
            return false;
        }
        if (!now.isAfter(session.getVerificationDeadline())) {
            return false;
        }
        session.markExpired(REASON_VERIFICATION_DEADLINE_PASSED);
        if (quota != null) {
            quota.release();
        }
        return true;
    }
}
