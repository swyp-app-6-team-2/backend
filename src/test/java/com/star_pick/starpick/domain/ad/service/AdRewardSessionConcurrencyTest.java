package com.star_pick.starpick.domain.ad.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ad.entity.AdRewardPlatform;
import com.star_pick.starpick.domain.ad.dto.request.AdRewardSessionCreateRequest;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResponse;
import com.star_pick.starpick.domain.ad.exception.AdRewardErrorCode;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
 * 세션 발급이 사용자 행 잠금으로 정말 줄 서는지 확인한다. REWARDED_AD_SSV.md §7,
 * {@code docs/issues/rewarded-ad/02-create-session.md} 완료 기준.
 *
 * <p>{@code RecipeIngestionConcurrencyTest} 와 같은 이유로 잠금이 조용히 빠지면 단일 스레드 테스트는
 * 전부 통과한다. 스레드 2개, 5~20초 타임아웃만 쓰며 오래 도는 백그라운드 프로세스는 만들지 않는다.
 */
@IntegrationTest
class AdRewardSessionConcurrencyTest {

    private static final Long OWNER = 997101L;

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

    private boolean isWaitingForLock(int pid) {
        return jdbcTemplate.queryForObject("""
                select coalesce(wait_event_type = 'Lock', false) from pg_stat_activity where pid = ?
                """, Boolean.class, pid);
    }

    private int sessionCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from ad_reward_session where user_id = ?", Integer.class, OWNER);
    }

    private int reservedCount() {
        return jdbcTemplate.queryForObject(
                "select reserved_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER);
    }

    @Test
    @Timeout(20)
    @DisplayName("같은 requestId로 동시 재시도해도 세션과 예약은 하나만 생긴다")
    void concurrentRetryCreatesOneSessionAndReservation() throws Exception {
        AdRewardSessionCreateRequest request = new AdRewardSessionCreateRequest(AdRewardPlatform.ANDROID, "req-1");
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AdRewardSessionResponse> first = executor.submit(() -> transactionTemplate.execute(status -> {
                AdRewardSessionResponse result = sessionService.createSession(OWNER, request);
                firstDone.countDown();
                awaitQuietly(commitFirst);
                return result;
            }));
            assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicInteger secondPid = new AtomicInteger();
            Future<AdRewardSessionResponse> second = executor.submit(() -> transactionTemplate.execute(status -> {
                secondPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                return sessionService.createSession(OWNER, request);
            }));
            // 두 번째 요청이 실제로 사용자 행 잠금에서 멈춘 것을 DB 에서 확인한 뒤 첫 요청을 커밋한다.
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> secondPid.get() != 0 && isWaitingForLock(secondPid.get()));
            assertThat(second.isDone()).isFalse();

            commitFirst.countDown();
            AdRewardSessionResponse firstResult = first.get(5, TimeUnit.SECONDS);
            AdRewardSessionResponse secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(secondResult.sessionId()).isEqualTo(firstResult.sessionId());
            assertThat(sessionCount()).isEqualTo(1);
            assertThat(reservedCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("서로 다른 requestId가 동시에 들어와도 진행 중 세션은 하나만 남는다")
    void concurrentDifferentRequestIdsRespectPendingLimit() throws Exception {
        AdRewardSessionCreateRequest requestA = new AdRewardSessionCreateRequest(AdRewardPlatform.ANDROID, "req-a");
        AdRewardSessionCreateRequest requestB = new AdRewardSessionCreateRequest(AdRewardPlatform.ANDROID, "req-b");
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AdRewardSessionResponse> first = executor.submit(() -> transactionTemplate.execute(status -> {
                AdRewardSessionResponse result = sessionService.createSession(OWNER, requestA);
                firstDone.countDown();
                awaitQuietly(commitFirst);
                return result;
            }));
            assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicInteger secondPid = new AtomicInteger();
            Future<AdRewardSessionResponse> second = executor.submit(() -> transactionTemplate.execute(status -> {
                secondPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                return sessionService.createSession(OWNER, requestB);
            }));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> secondPid.get() != 0 && isWaitingForLock(secondPid.get()));
            assertThat(second.isDone()).isFalse();

            commitFirst.countDown();
            AdRewardSessionResponse firstResult = first.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.status().name()).isEqualTo("PENDING");

            try {
                second.get(5, TimeUnit.SECONDS);
                throw new AssertionError("두 번째 요청은 진행 중 세션 제한에 걸려 실패해야 한다");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) e.getCause()).getErrorCode())
                        .isEqualTo(AdRewardErrorCode.AD_REWARD_SESSION_PENDING);
            }

            assertThat(sessionCount()).isEqualTo(1);
            assertThat(reservedCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 첫 트랜잭션을 풀어주지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
