package com.star_pick.starpick.domain.ad.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** "시청 세션 발급" 통합 테스트. REWARDED_AD_SSV.md §4.2. */
@IntegrationTest
class AdRewardSessionApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 997001L;
    private static final String PATH = "/api/v1/ads/rewards/sessions";

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

    private String body(String platform, String requestId) {
        return """
                {"platform": "%s", "requestId": "%s"}
                """.formatted(platform, requestId);
    }

    private int sessionCount(long userId) {
        return jdbc.queryForObject("select count(*) from ad_reward_session where user_id = ?", Integer.class, userId);
    }

    private void seedQuota(long userId, int granted, int reserved) {
        jdbc.update("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, ?, ?, ?)
                """, userId, LocalDate.now(SEOUL), granted, reserved);
    }

    @Test
    @DisplayName("신규 발급은 예약 1건을 만들고 세션 정보를 반환한다")
    void issuesNewSession() throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("광고 시청 세션을 발급했습니다."))
                .andExpect(jsonPath("$.data.sessionId").value(notNullValue()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.adUnitId").value("ca-app-pub-3940256099942544/5224354917"))
                .andExpect(jsonPath("$.data.rewardType").value("recipe_slot"))
                .andExpect(jsonPath("$.data.rewardAmount").value(2))
                .andExpect(jsonPath("$.data.quotaDate").value(LocalDate.now(SEOUL).toString()))
                .andExpect(jsonPath("$.data.expiresAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.verificationDeadline").value(notNullValue()));

        assertThat(sessionCount(OWNER)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select reserved_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("customData 는 sessionId 문자열과 같다")
    void customDataEqualsSessionId() throws Exception {
        String response = mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String sessionId = com.jayway.jsonpath.JsonPath.read(response, "$.data.sessionId");
        String customData = com.jayway.jsonpath.JsonPath.read(response, "$.data.customData");
        assertThat(customData).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("이 API는 레시피 저장 슬롯을 늘리지 않는다")
    void doesNotIncreaseRecipeSlots() throws Exception {
        Integer before = jdbc.queryForObject(
                "select recipe_slot_limit from users where user_id = ?", Integer.class, OWNER);

        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isOk());

        Integer after = jdbc.queryForObject(
                "select recipe_slot_limit from users where user_id = ?", Integer.class, OWNER);
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("같은 requestId 재시도는 새 예약 없이 기존 세션을 그대로 반환한다")
    void sameRequestIdIsIdempotent() throws Exception {
        String first = mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstSessionId = com.jayway.jsonpath.JsonPath.read(first, "$.data.sessionId");

        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(firstSessionId));

        assertThat(sessionCount(OWNER)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select reserved_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 requestId를 다른 platform으로 재요청하면 409다")
    void sameRequestIdDifferentPlatformConflicts() throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isOk());

        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("IOS", "req-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("AD_REWARD_REQUEST_ID_CONFLICT"));
    }

    @Test
    @DisplayName("새 requestId라도 당일 진행 중 세션이 있으면 409이고 예약은 늘지 않는다")
    void newRequestIdWhilePendingSessionExistsIsRejected() throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isOk());

        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("AD_REWARD_SESSION_PENDING"));

        assertThat(sessionCount(OWNER)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select reserved_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("지급·예약 횟수 합이 일일 한도에 도달하면 409이고 예약은 늘지 않는다")
    void dailyLimitReached() throws Exception {
        seedQuota(OWNER, 2, 1);

        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("AD_REWARD_DAILY_LIMIT_REACHED"));

        assertThat(jdbc.queryForObject(
                "select reserved_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER))
                .isEqualTo(1);
        assertThat(sessionCount(OWNER)).isZero();
    }

    @Test
    @DisplayName("토큰이 없거나 탈퇴한 사용자는 401이다")
    void rejectsUnauthenticatedAndDeletedUsers() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("ANDROID", "req-1")))
                .andExpect(status().isUnauthorized());

        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "req-1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("requestId가 비었으면 400 REQUEST_VALIDATION_FAILED다")
    void blankRequestIdIsRejected() throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANDROID", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("정의되지 않은 platform 값은 400 INVALID_REQUEST_FORMAT이다")
    void unknownPlatformIsRejected() throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WEB", "req-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }
}
