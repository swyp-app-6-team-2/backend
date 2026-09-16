package com.star_pick.starpick.domain.ad.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** "시청 세션 포기" 통합 테스트. REWARDED_AD_SSV.md §4.4. */
@IntegrationTest
class AdRewardCancelApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 999601L;
    private static final long OTHER_USER = 999602L;
    private static final String AD_UNIT = "ca-app-pub-3940256099942544/5224354917";

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER_USER);
    }

    @AfterEach
    void restore() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private String bearer(long userId) {
        return "Bearer " + jwt.generateTokens(userId).accessToken();
    }

    private String path(UUID sessionId) {
        return "/api/v1/ads/rewards/sessions/" + sessionId + "/cancel";
    }

    private String body(String reason) {
        return """
                {"reason": "%s"}
                """.formatted(reason);
    }

    private void seedQuota(long userId, LocalDate quotaDate, int granted, int reserved) {
        jdbc.update("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, ?, ?, ?)
                """, userId, quotaDate, granted, reserved);
    }

    private UUID seedSession(long userId, LocalDate quotaDate, String status) {
        Instant now = Instant.now();
        return jdbc.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_type, reward_amount, quota_date,
                     status, created_at, expires_at, verification_deadline)
                values (gen_random_uuid(), ?, ?, 'ANDROID', ?, 'recipe_slot', 2, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, userId, UUID.randomUUID().toString(), AD_UNIT, quotaDate, status,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plusSeconds(1800), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plus(1, ChronoUnit.DAYS), ZoneOffset.UTC));
    }

    private int reservedCount(long userId, LocalDate quotaDate) {
        return jdbc.queryForObject("""
                select reserved_count from ad_reward_daily_quota where user_id = ? and quota_date = ?
                """, Integer.class, userId, quotaDate);
    }

    private String sessionStatus(UUID sessionId) {
        return jdbc.queryForObject("select status from ad_reward_session where id = ?", String.class, sessionId);
    }

    @Test
    @DisplayName("PENDING 세션을 포기하면 취소로 전환하고 예약을 반환한다")
    void cancelsPendingSessionAndReleasesReservation() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 0, 1);
        UUID sessionId = seedSession(OWNER, today, "PENDING");

        mvc.perform(post(path(sessionId)).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER_ABANDONED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("광고 시청 세션을 포기했습니다."))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.reasonCode").value("USER_ABANDONED"))
                .andExpect(jsonPath("$.data.grantedAmount").value(0));

        assertThat(sessionStatus(sessionId)).isEqualTo("CANCELLED");
        assertThat(reservedCount(OWNER, today)).isZero();
    }

    @Test
    @DisplayName("이미 지급된 세션은 다시 취소하지 않고 현재 결과를 그대로 반환한다")
    void grantedSessionIsNotCancelled() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 1, 0);
        UUID sessionId = seedSession(OWNER, today, "GRANTED");

        mvc.perform(post(path(sessionId)).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LOAD_FAILED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GRANTED"));

        assertThat(sessionStatus(sessionId)).isEqualTo("GRANTED");
        assertThat(reservedCount(OWNER, today)).isZero();
    }

    @Test
    @DisplayName("이미 만료된 세션도 현재 결과를 그대로 반환하고 예약을 다시 건드리지 않는다")
    void expiredSessionIsNotDoubleReleased() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 0, 0);
        UUID sessionId = seedSession(OWNER, today, "EXPIRED");

        mvc.perform(post(path(sessionId)).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LOAD_FAILED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));

        assertThat(reservedCount(OWNER, today)).isZero();
    }

    @Test
    @DisplayName("반복 취소해도 예약은 음수가 되지 않는다")
    void repeatedCancelDoesNotGoNegative() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 0, 1);
        UUID sessionId = seedSession(OWNER, today, "PENDING");

        for (int i = 0; i < 3; i++) {
            mvc.perform(post(path(sessionId)).header("Authorization", bearer(OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("USER_DISMISSED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        assertThat(reservedCount(OWNER, today)).isZero();
    }

    @Test
    @DisplayName("없는 세션과 다른 사용자의 세션은 동일한 404다")
    void missingAndForeignSessionsReturnSame404() throws Exception {
        mvc.perform(post(path(UUID.randomUUID())).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LOAD_FAILED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("AD_REWARD_SESSION_NOT_FOUND"));

        UUID otherUsersSession = seedSession(OTHER_USER, LocalDate.now(SEOUL), "PENDING");
        mvc.perform(post(path(otherUsersSession)).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LOAD_FAILED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("AD_REWARD_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("정의되지 않은 사유는 400이다")
    void unknownReasonIsRejected() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "PENDING");

        mvc.perform(post(path(sessionId)).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOT_A_REASON")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("사유가 없으면 400 REQUEST_VALIDATION_FAILED다")
    void missingReasonIsRejected() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "PENDING");

        mvc.perform(post(path(sessionId)).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("토큰이 없거나 탈퇴한 사용자는 401이다")
    void rejectsUnauthenticatedAndDeletedUsers() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "PENDING");

        mvc.perform(post(path(sessionId)).contentType(MediaType.APPLICATION_JSON).content(body("LOAD_FAILED")))
                .andExpect(status().isUnauthorized());

        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        mvc.perform(post(path(sessionId)).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LOAD_FAILED")))
                .andExpect(status().isUnauthorized());
    }
}
