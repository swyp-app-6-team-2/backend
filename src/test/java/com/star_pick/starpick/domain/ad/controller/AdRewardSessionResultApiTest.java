package com.star_pick.starpick.domain.ad.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** "시청 세션 결과 조회" 통합 테스트. REWARDED_AD_SSV.md §4.3. */
@IntegrationTest
class AdRewardSessionResultApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 999501L;
    private static final long OTHER_USER = 999502L;
    private static final String AD_UNIT = "ca-app-pub-3940256099942544/5224354917";
    private static final String PATH = "/api/v1/ads/rewards/sessions/";

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

    /** 필요한 상태·사유·지급 시각을 직접 지정해 세션을 만든다. 실제로 아직 이 상태를 만들지 않는 경로(REJECTED·CANCELLED)도 API 는 다뤄야 한다. */
    private UUID seedSession(long userId, LocalDate quotaDate, String status, String reasonCode, Instant grantedAt) {
        Instant now = Instant.now();
        return jdbc.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_type, reward_amount, quota_date,
                     status, reason_code, created_at, expires_at, verification_deadline, granted_at)
                values (gen_random_uuid(), ?, ?, 'ANDROID', ?, 'recipe_slot', 2, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, userId, UUID.randomUUID().toString(), AD_UNIT, quotaDate, status, reasonCode,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plusSeconds(1800), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plus(1, ChronoUnit.DAYS), ZoneOffset.UTC),
                grantedAt == null ? null : OffsetDateTime.ofInstant(grantedAt, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("PENDING 세션은 미지급 필드가 비어 있다")
    void pendingSessionHasEmptyGrantFields() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "PENDING", null, null);

        mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("광고 시청 세션 결과를 조회했습니다."))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.data.quotaDate").value(LocalDate.now(SEOUL).toString()))
                .andExpect(jsonPath("$.data.grantedAmount").value(0))
                .andExpect(jsonPath("$.data.grantedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.recipeSlotLimit").value(10))
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(10));
    }

    @Test
    @DisplayName("GRANTED 세션은 지급량과 지급 시각을 반환한다")
    void grantedSessionReturnsGrantFields() throws Exception {
        Instant grantedAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "GRANTED", null, grantedAt);
        jdbc.update("update users set recipe_slot_limit = 12 where user_id = ?", OWNER);

        mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GRANTED"))
                .andExpect(jsonPath("$.data.grantedAmount").value(2))
                .andExpect(jsonPath("$.data.grantedAt").value(grantedAt.toString()))
                .andExpect(jsonPath("$.data.recipeSlotLimit").value(12));
    }

    @Test
    @DisplayName("EXPIRED 세션은 사유 코드만 있고 지급 필드는 비어 있다")
    void expiredSessionHasReasonCodeButNoGrant() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL).minusDays(1), "EXPIRED",
                "VERIFICATION_DEADLINE_PASSED", null);

        mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.reasonCode").value("VERIFICATION_DEADLINE_PASSED"))
                .andExpect(jsonPath("$.data.grantedAmount").value(0))
                .andExpect(jsonPath("$.data.grantedAt").value(nullValue()));
    }

    @Test
    @DisplayName("CANCELLED 세션도 조회할 수 있다")
    void cancelledSessionIsQueryable() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "CANCELLED", "USER_ABANDONED", null);

        mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.reasonCode").value("USER_ABANDONED"))
                .andExpect(jsonPath("$.data.grantedAmount").value(0));
    }

    @Test
    @DisplayName("REJECTED 세션도 조회할 수 있다")
    void rejectedSessionIsQueryable() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "REJECTED", "AD_UNIT_MISMATCH", null);

        mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reasonCode").value("AD_UNIT_MISMATCH"))
                .andExpect(jsonPath("$.data.grantedAmount").value(0));
    }

    @Test
    @DisplayName("세션 지급 날짜가 지나도 결과를 조회할 수 있다")
    void queryableAfterQuotaDatePasses() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL).minusDays(3), "GRANTED", null, Instant.now());

        mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quotaDate").value(LocalDate.now(SEOUL).minusDays(3).toString()));
    }

    @Test
    @DisplayName("없는 세션과 다른 사용자의 세션은 동일한 404다")
    void missingAndForeignSessionsReturnSame404() throws Exception {
        mvc.perform(get(PATH + UUID.randomUUID()).header("Authorization", bearer(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("AD_REWARD_SESSION_NOT_FOUND"));

        UUID otherUsersSession = seedSession(OTHER_USER, LocalDate.now(SEOUL), "PENDING", null, null);
        mvc.perform(get(PATH + otherUsersSession).header("Authorization", bearer(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("AD_REWARD_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("반복 조회로 상태나 슬롯이 바뀌지 않는다")
    void repeatedQueryDoesNotChangeState() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "PENDING", null, null);

        for (int i = 0; i < 3; i++) {
            mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.grantedAmount").value(0));
        }
        String finalStatus = jdbc.queryForObject("select status from ad_reward_session where id = ?",
                String.class, sessionId);
        org.assertj.core.api.Assertions.assertThat(finalStatus).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("토큰이 없거나 탈퇴한 사용자는 401이다")
    void rejectsUnauthenticatedAndDeletedUsers() throws Exception {
        UUID sessionId = seedSession(OWNER, LocalDate.now(SEOUL), "PENDING", null, null);

        mvc.perform(get(PATH + sessionId)).andExpect(status().isUnauthorized());

        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        mvc.perform(get(PATH + sessionId).header("Authorization", bearer(OWNER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("세션 id 형식이 잘못되면 400이다")
    void malformedSessionIdReturns400() throws Exception {
        mvc.perform(get(PATH + "not-a-uuid").header("Authorization", bearer(OWNER)))
                .andExpect(status().isBadRequest());
    }
}
