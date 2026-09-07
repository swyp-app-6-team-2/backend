package com.star_pick.starpick.domain.cooking.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.cooking.domain.CookHistory;
import com.star_pick.starpick.domain.cooking.repository.CookHistoryRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /api/v1/recipes/{recipeId}/cook-histories 통합 테스트. */
@IntegrationTest
class CookHistoryQueryApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private CookHistoryRepository cookHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;

    private Long recipeId;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
        recipeId = fixtures.saveRecipe(OWNER_ID);
    }

    private ResultActions read(Long targetRecipeId) throws Exception {
        return mockMvc.perform(get("/api/v1/recipes/{recipeId}/cook-histories", targetRecipeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private void save(Long targetRecipeId, String photoKey, String memo) {
        cookHistoryRepository.save(CookHistory.create(targetRecipeId, photoKey, memo));
    }

    /** cookedAt 은 서버가 정하므로 정렬 검증에는 DB 를 직접 고쳐 시각을 벌린다. */
    private void shiftCookedAt(String memo, String timestamp) {
        jdbcTemplate.update("update cook_history set cooked_at = ?::timestamp where memo = ?", timestamp, memo);
    }

    @Test
    @DisplayName("이력이 없으면 null 이 아니라 빈 배열이다")
    void returnsEmptyArray() throws Exception {
        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("요리 완료 기록을 조회했습니다."))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("최근 조리 순으로 정렬한다")
    void sortsByCookedAtDesc() throws Exception {
        save(recipeId, null, "가장 오래됨");
        save(recipeId, null, "중간");
        save(recipeId, null, "가장 최근");
        shiftCookedAt("가장 오래됨", "2026-08-20 09:00:00");
        shiftCookedAt("중간", "2026-08-23 10:30:00");
        shiftCookedAt("가장 최근", "2026-09-01 12:00:00");

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].memo").value("가장 최근"))
                .andExpect(jsonPath("$.data[1].memo").value("중간"))
                .andExpect(jsonPath("$.data[2].memo").value("가장 오래됨"));
    }

    @Test
    @DisplayName("조리 시각이 같으면 나중에 저장된 기록이 먼저 온다")
    void breaksTieByIdDesc() throws Exception {
        save(recipeId, null, "먼저 저장");
        save(recipeId, null, "나중 저장");
        jdbcTemplate.update("update cook_history set cooked_at = '2026-08-23 10:30:00'::timestamp");

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].memo").value("나중 저장"))
                .andExpect(jsonPath("$.data[1].memo").value("먼저 저장"));
    }

    @Test
    @DisplayName("사진이 있으면 photoKey 가 아니라 조회 URL 을 준다")
    void returnsPhotoUrl() throws Exception {
        String photoKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);
        save(recipeId, photoKey, null);

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].photoUrl").value(FakeObjectStorage.VIEW_URL_PREFIX + photoKey))
                .andExpect(jsonPath("$.data[0].photoKey").doesNotExist());
    }

    @Test
    @DisplayName("사진과 메모가 없으면 키는 있고 값은 null 이다")
    void keepsNullFields() throws Exception {
        save(recipeId, null, null);

        read(recipeId)
                .andExpect(status().isOk())
                // 키를 생략하지 않고 null 로 내려보내는 것이 계약이다.
                .andExpect(jsonPath("$.data[0]", Matchers.hasKey("photoUrl")))
                .andExpect(jsonPath("$.data[0]", Matchers.hasKey("memo")))
                .andExpect(jsonPath("$.data[0].photoUrl").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].memo").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("cookedAt 은 Z 로 끝나는 ISO 8601 UTC 다")
    void serializesCookedAtAsUtc() throws Exception {
        save(recipeId, null, "메모");

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cookedAt").value(Matchers.endsWith("Z")));
    }

    @Test
    @DisplayName("응답에 내부 식별자를 노출하지 않는다")
    void hidesIdentifier() throws Exception {
        save(recipeId, null, "메모");

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[0].cookHistoryId").doesNotExist())
                .andExpect(jsonPath("$.data[0].recipeId").doesNotExist());
    }

    @Test
    @DisplayName("다른 레시피의 이력은 섞이지 않는다")
    void isolatesByRecipe() throws Exception {
        Long anotherRecipeId = fixtures.saveRecipe(OWNER_ID);
        save(recipeId, null, "이 레시피");
        save(anotherRecipeId, null, "다른 레시피");

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].memo").value("이 레시피"));
    }

    @Test
    @DisplayName("없는 레시피면 404 RECIPE_NOT_FOUND 다")
    void rejectsMissingRecipe() throws Exception {
        read(99999999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 사용자의 레시피도 같은 404 RECIPE_NOT_FOUND 다")
    void rejectsOtherUsersRecipe() throws Exception {
        Long otherRecipeId = fixtures.saveRecipe(OTHER_ID);
        save(otherRecipeId, null, "남의 기록");

        read(otherRecipeId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/{recipeId}/cook-histories", recipeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }
}
