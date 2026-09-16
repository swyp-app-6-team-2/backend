package com.star_pick.starpick.domain.ad.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResultResponse;
import com.star_pick.starpick.domain.ad.entity.AdRewardCancelReason;
import com.star_pick.starpick.domain.ad.entity.AdRewardSessionStatus;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 세션에 대한 동시 취소 요청이 예약을 한 번만 반환하는지 확인한다. REWARDED_AD_SSV.md §4.4, §7.
 *
 * <p>{@code AdRewardSessionConcurrencyTest} 와 같은 이유로 잠금이 조용히 빠지면 단일 스레드
 * 테스트는 전부 통과한다. 스레드 2개, 20초 타임아웃만 쓴다.
 */
@IntegrationTest
class AdRewardCancelConcurrencyTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 999801L;
    private static final String AD_UNIT = "ca-app-pub-3940256099942544/5224354917";

    @Autowired
    private AdRewardSessionService sessionService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
    }

    private UUID seedSession(LocalDate quotaDate) {
        Instant now = Instant.now();
        return jdbcTemplate.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_type, reward_amount, quota_date,
                     status, created_at, expires_at, verification_deadline)
                values (gen_random_uuid(), ?, ?, 'ANDROID', ?, 'recipe_slot', 2, ?, 'PENDING', ?, ?, ?)
                returning id
                """, UUID.class, OWNER, UUID.randomUUID().toString(), AD_UNIT, quotaDate,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plusSeconds(1800), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plus(1, ChronoUnit.DAYS), ZoneOffset.UTC));
    }

    private void seedQuota(LocalDate quotaDate) {
        jdbcTemplate.update("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, ?, 0, 1)
                """, OWNER, quotaDate);
    }

    private int reservedCount(LocalDate quotaDate) {
        return jdbcTemplate.queryForObject("""
                select reserved_count from ad_reward_daily_quota where user_id = ? and quota_date = ?
                """, Integer.class, OWNER, quotaDate);
    }

    private boolean isWaitingForLock(int pid) {
        return jdbcTemplate.queryForObject("""
                select coalesce(wait_event_type = 'Lock', false) from pg_stat_activity where pid = ?
                """, Boolean.class, pid);
    }

    @Test
    @Timeout(20)
    @DisplayName("같은 세션을 동시에 취소해도 예약은 한 번만 반환된다")
    void concurrentCancelReleasesReservationOnce() throws Exception {
        LocalDate quotaDate = LocalDate.now(SEOUL);
        seedQuota(quotaDate);
        UUID sessionId = seedSession(quotaDate);

        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AdRewardSessionResultResponse> first = executor.submit(() -> transactionTemplate.execute(status -> {
                AdRewardSessionResultResponse result =
                        sessionService.cancelSession(OWNER, sessionId, AdRewardCancelReason.LOAD_FAILED);
                firstDone.countDown();
                awaitQuietly(commitFirst);
                return result;
            }));
            assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicInteger secondPid = new AtomicInteger();
            Future<AdRewardSessionResultResponse> second = executor.submit(() -> transactionTemplate.execute(status -> {
                secondPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                return sessionService.cancelSession(OWNER, sessionId, AdRewardCancelReason.USER_DISMISSED);
            }));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> secondPid.get() != 0 && isWaitingForLock(secondPid.get()));
            assertThat(second.isDone()).isFalse();

            commitFirst.countDown();
            AdRewardSessionResultResponse firstResult = first.get(5, TimeUnit.SECONDS);
            AdRewardSessionResultResponse secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.status()).isEqualTo(AdRewardSessionStatus.CANCELLED);
            // 두 번째 요청은 이미 취소된 결과를 그대로 받는다 — 사유가 첫 번째 것으로 유지된다.
            assertThat(secondResult.status()).isEqualTo(AdRewardSessionStatus.CANCELLED);
            assertThat(secondResult.reasonCode()).isEqualTo(firstResult.reasonCode());
            assertThat(reservedCount(quotaDate)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 첫 취소 트랜잭션을 풀어주지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
