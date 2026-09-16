package com.star_pick.starpick.domain.ad.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** "광고 보상 상태 조회" 통합 테스트. REWARDED_AD_SSV.md §4.1. */
@IntegrationTest
class AdRewardStatusApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 999001L;
    private static final String AD_UNIT = "ca-app-pub-3940256099942544/5224354917";
    private static final String PATH = "/api/v1/ads/rewards/status";

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
    }

    @AfterEach
    void restore() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private String bearer(long userId) {
        return "Bearer " + jwt.generateTokens(userId).accessToken();
    }

    private void seedQuota(LocalDate quotaDate, int granted, int reserved) {
        jdbc.update("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, ?, ?, ?)
                """, OWNER, quotaDate, granted, reserved);
    }

    private UUID seedSession(LocalDate quotaDate, String status) {
        Instant now = Instant.now();
        return jdbc.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_type, reward_amount, quota_date,
                     status, created_at, expires_at, verification_deadline)
                values (gen_random_uuid(), ?, ?, 'ANDROID', ?, 'recipe_slot', 2, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, OWNER, UUID.randomUUID().toString(), AD_UNIT, quotaDate, status,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plusSeconds(1800), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plus(1, ChronoUnit.DAYS), ZoneOffset.UTC));
    }

    private String expectedResetsAt() {
        LocalDate today = LocalDate.now(SEOUL);
        return ZonedDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT, SEOUL).toInstant().toString();
    }

    @Test
    @DisplayName("이력이 없으면 지급·예약 횟수는 0이고 전부 시청 가능하다")
    void noHistoryReturnsZerosAndWatchable() throws Exception {
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("광고 보상 상태를 조회했습니다."))
                .andExpect(jsonPath("$.data.recipeSlotLimit").value(10))
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(10))
                .andExpect(jsonPath("$.data.dailyRewardCount").value(0))
                .andExpect(jsonPath("$.data.dailyRewardLimit").value(3))
                .andExpect(jsonPath("$.data.reservedCount").value(0))
                .andExpect(jsonPath("$.data.remainingRewardCount").value(3))
                .andExpect(jsonPath("$.data.availableWatchCount").value(3))
                .andExpect(jsonPath("$.data.canWatchAd").value(true))
                .andExpect(jsonPath("$.data.unavailableReason").value(nullValue()))
                .andExpect(jsonPath("$.data.quotaDate").value(LocalDate.now(SEOUL).toString()))
                .andExpect(jsonPath("$.data.resetsAt").value(expectedResetsAt()))
                .andExpect(jsonPath("$.data.pendingSessions").value(empty()));
    }

    @Test
    @DisplayName("일부 지급된 상태를 정확히 반영한다")
    void partiallyGranted() throws Exception {
        seedQuota(LocalDate.now(SEOUL), 1, 0);

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyRewardCount").value(1))
                .andExpect(jsonPath("$.data.remainingRewardCount").value(2))
                .andExpect(jsonPath("$.data.availableWatchCount").value(2))
                .andExpect(jsonPath("$.data.canWatchAd").value(true));
    }

    @Test
    @DisplayName("3회 모두 지급되면 한도 소진 사유와 함께 시청 불가를 반환한다")
    void dailyLimitReached() throws Exception {
        seedQuota(LocalDate.now(SEOUL), 3, 0);

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyRewardCount").value(3))
                .andExpect(jsonPath("$.data.remainingRewardCount").value(0))
                .andExpect(jsonPath("$.data.availableWatchCount").value(0))
                .andExpect(jsonPath("$.data.canWatchAd").value(false))
                .andExpect(jsonPath("$.data.unavailableReason").value("DAILY_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("당일 진행 중 세션이 있으면 잔여 횟수가 남아 있어도 지급 대기 사유로 시청을 막는다")
    void rewardPendingBlocksWatchingEvenWithRemainingCount() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(today, 0, 1);
        UUID sessionId = seedSession(today, "PENDING");

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableWatchCount").value(2))
                .andExpect(jsonPath("$.data.canWatchAd").value(false))
                .andExpect(jsonPath("$.data.unavailableReason").value("REWARD_PENDING"))
                .andExpect(jsonPath("$.data.pendingSessions[0].sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.pendingSessions[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("전날의 미완료 세션은 복구용으로 반환되지만 당일 카운터·시청 가능 여부에는 영향을 주지 않는다")
    void previousDayPendingSessionDoesNotBlockToday() throws Exception {
        LocalDate yesterday = LocalDate.now(SEOUL).minusDays(1);
        UUID staleSessionId = seedSession(yesterday, "PENDING");

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyRewardCount").value(0))
                .andExpect(jsonPath("$.data.reservedCount").value(0))
                .andExpect(jsonPath("$.data.canWatchAd").value(true))
                .andExpect(jsonPath("$.data.unavailableReason").value(nullValue()))
                .andExpect(jsonPath("$.data.pendingSessions[0].sessionId").value(staleSessionId.toString()))
                .andExpect(jsonPath("$.data.pendingSessions[0].quotaDate").value(yesterday.toString()));
    }

    @Test
    @DisplayName("잔여 슬롯은 누적 등록 횟수 기준을 그대로 쓴다")
    void remainingRecipeSlotsUsesExistingCalculation() throws Exception {
        jdbc.update("""
                update users set recipe_slot_limit = 12, cumulative_recipe_count = 5 where user_id = ?
                """, OWNER);

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipeSlotLimit").value(12))
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(7));
    }

    @Test
    @DisplayName("조회는 지급 횟수나 슬롯을 바꾸지 않는다")
    void getDoesNotChangeState() throws Exception {
        seedQuota(LocalDate.now(SEOUL), 1, 1);

        for (int i = 0; i < 2; i++) {
            mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dailyRewardCount").value(1))
                    .andExpect(jsonPath("$.data.reservedCount").value(1));
        }
        Integer granted = jdbc.queryForObject(
                "select granted_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER);
        Integer reserved = jdbc.queryForObject(
                "select reserved_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER);
        assertThat(granted).isEqualTo(1);
        assertThat(reserved).isEqualTo(1);
    }

    @Test
    @DisplayName("토큰이 없거나 탈퇴한 사용자는 401이다")
    void rejectsUnauthenticatedAndDeletedUsers() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isUnauthorized());
    }
}
