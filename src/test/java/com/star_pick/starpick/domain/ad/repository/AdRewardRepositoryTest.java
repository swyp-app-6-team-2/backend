package com.star_pick.starpick.domain.ad.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ad.entity.AdRewardDailyQuota;
import com.star_pick.starpick.domain.ad.entity.AdRewardPlatform;
import com.star_pick.starpick.domain.ad.entity.AdRewardSession;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.domain.ad.entity.AdRewardTransaction;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 마이그레이션이 만든 스키마 위에서 세 Entity·Repository 가 실제로 읽고 쓰는지 확인한다.
 * 제약 위반 자체는 {@link com.star_pick.starpick.domain.ad.AdRewardSchemaTest} 가 raw SQL 로 본다.
 */
@IntegrationTest
class AdRewardRepositoryTest {

    private static final long USER_ID = 1L;
    private static final LocalDate QUOTA_DATE = LocalDate.of(2026, 9, 16);

    @Autowired
    private AdRewardDailyQuotaRepository quotaRepository;
    @Autowired
    private AdRewardSessionRepository sessionRepository;
    @Autowired
    private AdRewardTransactionRepository transactionRepository;
    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(USER_ID);
    }

    // findForUpdate/findByIdForUpdate 는 PESSIMISTIC_WRITE 라 활성 트랜잭션이 있어야 한다.
    // 실제 호출자는 항상 @Transactional 서비스 메서드 안에서 부른다(UserRepository.findByIdForUpdate 와 같다).
    @Test
    @Transactional
    @DisplayName("daily_quota 는 저장·조회되고 findForUpdate 로 잠글 수 있다")
    void dailyQuotaRoundTrip() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        quotaRepository.save(AdRewardDailyQuota.create(USER_ID, QUOTA_DATE, now));

        assertThat(quotaRepository.findByUserIdAndQuotaDate(USER_ID, QUOTA_DATE))
                .isPresent()
                .get()
                .satisfies(quota -> {
                    assertThat(quota.getGrantedCount()).isZero();
                    assertThat(quota.getReservedCount()).isZero();
                });

        assertThat(quotaRepository.findForUpdate(USER_ID, QUOTA_DATE)).isPresent();
        assertThat(quotaRepository.findForUpdate(USER_ID, QUOTA_DATE.plusDays(1))).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("session 은 애플리케이션이 만든 UUID로 저장되고(merge 가 아니다) findByIdForUpdate 로 잠글 수 있다")
    void sessionRoundTrip() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AdRewardSession session = AdRewardSession.issue(USER_ID, "req-1", AdRewardPlatform.ANDROID,
                "unit", "recipe_slot", 2, QUOTA_DATE, now, now.plusSeconds(1800), now.plus(1, ChronoUnit.DAYS));

        AdRewardSession saved = sessionRepository.save(session);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(AdRewardSessionStatus.PENDING);
        assertThat(sessionRepository.findByIdForUpdate(saved.getId())).isPresent();
        assertThat(sessionRepository.findByUserIdAndRequestId(USER_ID, "req-1")).isPresent();
        assertThat(sessionRepository.findByIdAndUserId(saved.getId(), USER_ID)).isPresent();
        assertThat(sessionRepository.findByIdAndUserId(saved.getId(), USER_ID + 1)).isEmpty();
        assertThat(sessionRepository.findByUserIdAndQuotaDateAndStatus(USER_ID, QUOTA_DATE,
                AdRewardSessionStatus.PENDING)).containsExactly(saved);
    }

    @Test
    @DisplayName("transaction 은 거래 ID로 중복 여부를 확인할 수 있다")
    void transactionRoundTrip() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AdRewardSession session = sessionRepository.save(AdRewardSession.issue(USER_ID, "req-1",
                AdRewardPlatform.ANDROID, "unit", "recipe_slot", 2, QUOTA_DATE, now, now.plusSeconds(1800),
                now.plus(1, ChronoUnit.DAYS)));

        assertThat(transactionRepository.existsByTransactionId("txn-1")).isFalse();

        UUID sessionId = session.getId();
        transactionRepository.save(AdRewardTransaction.granted("txn-1", sessionId, USER_ID, "unit", "recipe_slot",
                2, now, now, now, 2, now));

        assertThat(transactionRepository.existsByTransactionId("txn-1")).isTrue();
        assertThat(transactionRepository.findByTransactionId("txn-1"))
                .isPresent()
                .get()
                .satisfies(txn -> {
                    assertThat(txn.getGrantedAmount()).isEqualTo(2);
                    assertThat(txn.getSessionId()).isEqualTo(sessionId);
                });
    }
}
