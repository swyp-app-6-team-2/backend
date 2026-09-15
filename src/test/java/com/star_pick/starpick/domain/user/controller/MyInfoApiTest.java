package com.star_pick.starpick.domain.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.user.service.UserRecipeStatsService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "내 정보 조회" 통합 테스트.
 *
 * <p>Profile 은 {@code TestFixtures.seedUser()} 가 만들지 않는다(users 행만 보장한다). 그래서
 * "Profile 없는 유저" 케이스는 별도 세팅 없이 {@code seedUser()}만으로 재현되고, "Profile 있는
 * 유저" 케이스만 이 파일 안에서 직접 insert 한다. 한 곳에서만 쓰는 헬퍼라 TestFixtures 에는
 * 옮기지 않는다(그 파일의 원칙과 동일).
 */
@IntegrationTest
class MyInfoApiTest {
    private static final long OWNER = 996001L;
    private static final String PATH = "/api/v1/users/me";

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRecipeStatsService recipeStats;

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

    private void seedProfile(long userId, String nickname, String profileImageUrl) {
        jdbc.update("""
                insert into profiles (user_id, nickname, profile_image_url, created_at, updated_at)
                values (?, ?, ?, now(), now())
                """, userId, nickname, profileImageUrl);
    }

    private void seedRecipeStats(long userId, int slotLimit, int activeCount, int cumulativeCount) {
        jdbc.update("""
                update users
                set recipe_slot_limit = ?, active_recipe_count = ?, cumulative_recipe_count = ?
                where user_id = ?
                """, slotLimit, activeCount, cumulativeCount, userId);
    }

    @Test
    void returnsProfileFieldsWhenProfileExists() throws Exception {
        seedProfile(OWNER, "별따먹는사람", "https://example.com/profile.jpg");

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 정보 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.userId").value(OWNER))
                .andExpect(jsonPath("$.data.nickname").value("별따먹는사람"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://example.com/profile.jpg"));
    }

    @Test
    void returnsNullNicknameAndImageWhenProfileMissing() throws Exception {
        // seedUser()만 호출된 상태 — Profile row 자체가 없다.
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(nullValue()))
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));
    }

    @Test
    void remainingSlotsIsLimitMinusActiveCount() throws Exception {
        seedRecipeStats(OWNER, 12, 5, 8);

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(7))
                .andExpect(jsonPath("$.data.cumulativeRecipeCount").value(8));
    }

    @Test
    void cumulativeCountSurvivesDeletionButActiveCountDecreases() throws Exception {
        recipeStats.onRecipeCreated(OWNER);
        recipeStats.onRecipeCreated(OWNER);
        recipeStats.onRecipeCreated(OWNER);
        recipeStats.onRecipeDeleted(OWNER);

        // 기본 슬롯 10개, active 3개 등록 후 1개 삭제 → active 2개, cumulative는 삭제와 무관하게 3 유지.
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(8))
                .andExpect(jsonPath("$.data.cumulativeRecipeCount").value(3));
    }

    @Test
    void decreaseNeverGoesBelowZeroEvenWithoutMatchingCreate() throws Exception {
        recipeStats.onRecipeDeleted(OWNER);
        recipeStats.onRecipeDeleted(OWNER);

        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(10))
                .andExpect(jsonPath("$.data.cumulativeRecipeCount").value(0));
    }

    @Test
    void rejectsUnauthorizedWrongTokenMissingAndDeletedUsers() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        mvc.perform(get(PATH).header("Authorization", "Bearer " + jwt.generateTokens(OWNER).refreshToken()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(PATH).header("Authorization", bearer(Long.MAX_VALUE)))
                .andExpect(status().isUnauthorized());

        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getDoesNotChangeState() throws Exception {
        seedProfile(OWNER, "별따먹는사람", null);
        seedRecipeStats(OWNER, 10, 3, 5);

        for (int i = 0; i < 2; i++) {
            mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remainingRecipeSlots").value(7))
                    .andExpect(jsonPath("$.data.cumulativeRecipeCount").value(5));
        }
    }
}