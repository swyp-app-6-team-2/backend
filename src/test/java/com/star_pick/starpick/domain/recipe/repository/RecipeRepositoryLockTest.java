package com.star_pick.starpick.domain.recipe.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수정 경로의 비관적 쓰기 잠금이 실제로 걸리는지 확인한다.
 *
 * <p>수정과 삭제는 대상 Recipe 행을 잠가 직렬화한다는 것이 확정 계약이다. 잠금이 조용히
 * 사라져도 단일 스레드 테스트는 전부 통과하므로 이 테스트가 유일한 방어선이다.
 */
@IntegrationTest
class RecipeRepositoryLockTest {

    private static final Long OWNER_ID = 1L;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    private Long recipeId;

    @BeforeEach
    void setUp() {
        recipeRepository.deleteAll();
        fixtures.seedUser(OWNER_ID);
        recipeId = recipeRepository.save(
                Recipe.createManual(OWNER_ID, "김치찌개", RecipeCategory.KOREAN, null, null, null)).getId();
    }

    @Test
    @Timeout(30)
    @DisplayName("이미 잠긴 행은 다른 트랜잭션이 잠글 수 없다")
    void secondLockerCannotAcquire() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread holder = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            recipeRepository.findByIdAndUserIdForUpdate(recipeId, OWNER_ID).orElseThrow();
            locked.countDown();
            try {
                done.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();

        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("set local lock_timeout = '500ms'");
                recipeRepository.findByIdAndUserIdForUpdate(recipeId, OWNER_ID);
            })).isInstanceOf(CannotAcquireLockException.class);
        } finally {
            done.countDown();
            holder.join(10_000);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("잠기지 않은 행은 곧바로 잠글 수 있다")
    void unlockedRowIsAcquiredImmediately() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("set local lock_timeout = '500ms'");
            assertThat(recipeRepository.findByIdAndUserIdForUpdate(recipeId, OWNER_ID)).isPresent();
        });
    }
}
