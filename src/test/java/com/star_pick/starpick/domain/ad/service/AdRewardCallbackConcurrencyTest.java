package com.star_pick.starpick.domain.ad.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.FakeAdRewardCallbackVerifier;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 거래 ID 를 가진 SSV 콜백이 동시에(예: Google 의 자동 재시도) 도착해도 한 번만 지급하는지
 * 확인한다. REWARDED_AD_SSV.md §7, {@code docs/issues/rewarded-ad/03-ssv-callback.md} 완료 기준.
 *
 * <p>{@code RecipeIngestionConcurrencyTest}·{@code AdRewardSessionConcurrencyTest} 와 같은 이유로
 * 잠금이 조용히 빠지면 단일 스레드 테스트는 전부 통과한다. 스레드 2개, 20초 타임아웃만 쓴다.
 */
@IntegrationTest
class AdRewardCallbackConcurrencyTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 998101L;
    private static final String AD_UNIT = "ca-app-pub-3940256099942544/5224354917";

    @Autowired
    private AdRewardCallbackService callbackService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private FakeAdRewardCallbackVerifier verifier;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
    }

    private UUID seedSession() {
        Instant now = Instant.now();
        return jdbcTemplate.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_type, reward_amount, quota_date,
                     status, created_at, expires_at, verification_deadline)
                values (gen_random_uuid(), ?, ?, 'ANDROID', ?, 'recipe_slot', 2, ?, 'PENDING', ?, ?, ?)
                returning id
                """, UUID.class, OWNER, UUID.randomUUID().toString(), AD_UNIT, LocalDate.now(SEOUL),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plusSeconds(1800), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plus(1, ChronoUnit.DAYS), ZoneOffset.UTC));
    }

    private void seedQuota() {
        jdbcTemplate.update("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, ?, 0, 1)
                """, OWNER, LocalDate.now(SEOUL));
    }

    private Map<String, String[]> paramsFor(UUID sessionId, String transactionId) {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("ad_unit", new String[]{AD_UNIT});
        params.put("custom_data", new String[]{sessionId.toString()});
        params.put("reward_amount", new String[]{"2"});
        params.put("reward_item", new String[]{"recipe_slot"});
        params.put("timestamp", new String[]{String.valueOf(Instant.now().toEpochMilli())});
        params.put("transaction_id", new String[]{transactionId});
        return params;
    }

    private int quotaGranted() {
        return jdbcTemplate.queryForObject(
                "select granted_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER);
    }

    private int recipeSlotLimit() {
        return jdbcTemplate.queryForObject(
                "select recipe_slot_limit from users where user_id = ?", Integer.class, OWNER);
    }

    private int transactionCount(String transactionId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from ad_reward_transaction where transaction_id = ?", Integer.class,
                transactionId);
    }

    @Test
    @Timeout(20)
    @DisplayName("같은 거래 ID가 동시에 들어와도 슬롯은 한 번만 +2 된다")
    void concurrentSameTransactionIdGrantsOnce() throws Exception {
        seedQuota();
        UUID sessionId = seedSession();
        String transactionId = "txn-concurrent-1";
        int slotBefore = recipeSlotLimit();

        // 먼저 도착한 콜백이 서명 검증(실제로는 공개키 조회 등 네트워크 호출을 포함하는 구간) 중에
        // 멈춰 있는 동안, 뒤이어 도착한 같은 거래의 콜백이 먼저 끝까지 처리되게 만든다.
        CountDownLatch firstEnteredVerify = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        verifier.onVerify(() -> {
            if (firstCall.compareAndSet(true, false)) {
                firstEnteredVerify.countDown();
                awaitQuietly(releaseFirst);
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AdRewardCallbackOutcome> first = executor.submit(() ->
                    callbackService.processCallback(rawQuery(paramsFor(sessionId, transactionId)),
                            paramsFor(sessionId, transactionId)));
            assertThat(firstEnteredVerify.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AdRewardCallbackOutcome> second = executor.submit(() ->
                    callbackService.processCallback(rawQuery(paramsFor(sessionId, transactionId)),
                            paramsFor(sessionId, transactionId)));
            AdRewardCallbackOutcome secondOutcome = second.get(5, TimeUnit.SECONDS);
            assertThat(secondOutcome).isEqualTo(AdRewardCallbackOutcome.PROCESSED);

            releaseFirst.countDown();
            AdRewardCallbackOutcome firstOutcome = first.get(5, TimeUnit.SECONDS);
            assertThat(firstOutcome).isEqualTo(AdRewardCallbackOutcome.PROCESSED);
        } finally {
            executor.shutdownNow();
        }

        assertThat(recipeSlotLimit()).isEqualTo(slotBefore + 2);
        assertThat(quotaGranted()).isEqualTo(1);
        assertThat(transactionCount(transactionId)).isOne();
    }

    private static String rawQuery(Map<String, String[]> params) {
        StringBuilder builder = new StringBuilder();
        params.forEach((key, values) -> {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(key).append('=').append(values[0]);
        });
        return builder.toString();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 첫 콜백을 풀어주지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
