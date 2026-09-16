package com.star_pick.starpick.domain.ad.worker;

import com.star_pick.starpick.domain.ad.repository.AdRewardSessionRepository;
import com.star_pick.starpick.domain.ad.service.AdRewardExpiryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 검증 수신 마감을 넘긴 세션을 주기적으로 정리한다. REWARDED_AD_SSV.md §2.1, §6.
 *
 * <p>{@code IngestionMaintenance} 와 같은 구조다 — {@code @Scheduled} 는 {@link AdRewardSchedule}
 * 에만 두고 이 Bean 은 조건 없이 등록해 테스트가 직접 호출할 수 있게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdRewardMaintenance {

    private static final int EXPIRE_BATCH_SIZE = 200;

    private final AdRewardSessionRepository sessions;
    private final AdRewardExpiryService expiryService;
    private final Clock clock;

    /**
     * 여러 인스턴스가 동시에 돌아도 안전하다 — 후보 id 를 건별로 잠그므로(§7), 다른 인스턴스가
     * 먼저 처리한 행은 {@code PENDING} 이 아니게 되어 {@code expireIfDue} 가 false 를 돌려준다.
     */
    public void expireOverdueSessions() {
        Instant now = clock.instant();
        List<UUID> candidates = sessions.findExpiredCandidateIds(now, PageRequest.of(0, EXPIRE_BATCH_SIZE));
        int expired = 0;
        for (UUID id : candidates) {
            try {
                if (expiryService.expireOne(id, now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // 건별로 잡는다. 한 건이 던져도 나머지 후보와 다음 주기 재시도에 영향이 없다.
                log.warn("광고 보상 세션 만료 처리에 실패했습니다. 다음 주기에 다시 시도합니다. sessionId={}", id, e);
            }
        }
        if (expired > 0) {
            log.info("검증 마감을 넘긴 광고 보상 세션을 만료시켰습니다. expired={}, candidates={}", expired, candidates.size());
        }
    }
}
