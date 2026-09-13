package com.star_pick.starpick.domain.recipe.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.star_pick.starpick.domain.recipe.controller.request.RecipeCreateRequest;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Duration;
import java.util.List;
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
 * 같은 IngestionJob 의 동시 저장이 Job 행 잠금으로 줄 서는지 확인한다.
 *
 * <p>잠금이 조용히 빠져도 단일 스레드 테스트는 전부 통과한다. UNIQUE 가 막아 주긴 하지만 그때 두 번째
 * 요청은 200 이 아니라 500 이 된다.
 */
@IntegrationTest
class RecipeIngestionConcurrencyTest {

    private static final Long OWNER_ID = 1L;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
    }

    /**
     * 해당 DB 세션이 잠금을 기다리는 중인지. 시간 초과만으로 "기다렸다"고 판정하면, 두 번째 스레드가
     * 늦게 시작한 것만으로도 통과해 잠금이 빠진 것을 못 잡는다. 세션을 pid 로 특정해 다른 대기
     * 세션과 헷갈리지 않게 한다.
     */
    private boolean isWaitingForLock(int pid) {
        return jdbcTemplate.queryForObject("""
                select coalesce(wait_event_type = 'Lock', false) from pg_stat_activity where pid = ?
                """, Boolean.class, pid);
    }

    @Test
    @Timeout(20)
    @DisplayName("같은 Job 으로 동시에 저장하면 뒤 요청은 잠금을 기다렸다가 기존 레시피를 받는다")
    void concurrentRequestsCreateOneRecipe() throws Exception {
        Long jobId = fixtures.saveReadyImageJob(OWNER_ID).getId();
        RecipeCreateRequest request = new RecipeCreateRequest(jobId, "김치찌개", RecipeCategory.KOREAN,
                null, null, null, null, List.of(), List.of());
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RecipeCreateResult> first = executor.submit(() -> transactionTemplate.execute(status -> {
                RecipeCreateResult result = recipeService.create(OWNER_ID, request);
                firstSaved.countDown();
                awaitQuietly(commitFirst);
                return result;
            }));
            assertThat(firstSaved.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicInteger secondPid = new AtomicInteger();
            Future<RecipeCreateResult> second = executor.submit(() -> transactionTemplate.execute(status -> {
                secondPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                return recipeService.create(OWNER_ID, request);
            }));
            // 두 번째 요청의 세션이 실제로 Job 행 잠금에서 멈춘 것을 DB 에서 확인한 뒤 첫 요청을 커밋한다.
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> secondPid.get() != 0 && isWaitingForLock(secondPid.get()));
            assertThat(second.isDone()).isFalse();

            commitFirst.countDown();
            RecipeCreateResult firstResult = first.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.created()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isEqualTo(new RecipeCreateResult(firstResult.recipeId(), false));
            assertThat(recipeRepository.count()).isEqualTo(1);
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
