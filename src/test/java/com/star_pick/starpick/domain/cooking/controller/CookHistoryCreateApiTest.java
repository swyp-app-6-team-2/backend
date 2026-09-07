package com.star_pick.starpick.domain.cooking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.cooking.repository.CookHistoryRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/** POST /api/v1/recipes/{recipeId}/cook-histories 통합 테스트. */
@IntegrationTest
class CookHistoryCreateApiTest {

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
    private FakeObjectStorage objectStorage;

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

    private ResultActions create(Long targetRecipeId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/recipes/{recipeId}/cook-histories", targetRecipeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions create(String body) throws Exception {
        return create(recipeId, body);
    }

    @Test
    @DisplayName("빈 객체만 보내도 201 이고 data 는 null 이다")
    void createsWithEmptyBody() throws Exception {
        create("{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("요리 완료 기록이 생성되었습니다."))
                // 계약은 "값이 없다"가 아니라 "data 가 null"이다. doesNotExist 만으로는 키 생략과 구분되지 않는다.
                .andExpect(content().json("""
                        {"status":201,"message":"요리 완료 기록이 생성되었습니다.","data":null}
                        """, JsonCompareMode.STRICT));

        Map<String, Object> saved = jdbcTemplate.queryForMap("select * from cook_history");
        assertThat(saved.get("recipe_id")).isEqualTo(recipeId);
        assertThat(saved.get("cooked_at")).isNotNull();
        assertThat(saved.get("photo_key")).isNull();
        assertThat(saved.get("memo")).isNull();
    }

    @Test
    @DisplayName("메모만 보내면 메모가 저장된다")
    void createsWithMemoOnly() throws Exception {
        create("""
                {"memo":"다음엔 조금 덜 맵게"}
                """)
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("select memo from cook_history", String.class))
                .isEqualTo("다음엔 조금 덜 맵게");
    }

    @Test
    @DisplayName("업로드까지 마친 사진 Key 를 보내면 연결된다")
    void attachesPhoto() throws Exception {
        String photoKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);

        create("""
                {"photoKey":"%s"}
                """.formatted(photoKey))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("select photo_key from cook_history", String.class))
                .isEqualTo(photoKey);
        assertThat(fixtures.isAttached(photoKey)).isTrue();
    }

    @Test
    @DisplayName("발급만 하고 업로드하지 않은 Key 는 400 COOK_HISTORY_PHOTO_INVALID 다")
    void rejectsNotUploadedKey() throws Exception {
        String photoKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);
        objectStorage.clear();

        create("""
                {"photoKey":"%s"}
                """.formatted(photoKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("COOK_HISTORY_PHOTO_INVALID"));

        assertThat(cookHistoryRepository.count()).isZero();
    }

    @Test
    @DisplayName("다른 사용자가 발급받은 Key 는 400 COOK_HISTORY_PHOTO_INVALID 다")
    void rejectsOtherUsersKey() throws Exception {
        String photoKey = fixtures.uploadedKey(OTHER_ID, UploadPurpose.COOK_HISTORY_PHOTO);

        create("""
                {"photoKey":"%s"}
                """.formatted(photoKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("COOK_HISTORY_PHOTO_INVALID"));

        assertThat(fixtures.isAttached(photoKey)).isFalse();
    }

    @Test
    @DisplayName("다른 용도로 발급된 Key 는 400 COOK_HISTORY_PHOTO_INVALID 다")
    void rejectsWrongPurposeKey() throws Exception {
        String coverKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);

        create("""
                {"photoKey":"%s"}
                """.formatted(coverKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("COOK_HISTORY_PHOTO_INVALID"));

        assertThat(fixtures.isAttached(coverKey)).isFalse();
    }

    @Test
    @DisplayName("이미 연결된 Key 는 409 COOK_HISTORY_PHOTO_ALREADY_USED 다")
    void rejectsAlreadyAttachedKey() throws Exception {
        String photoKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);
        create("""
                {"photoKey":"%s"}
                """.formatted(photoKey)).andExpect(status().isCreated());

        create("""
                {"photoKey":"%s"}
                """.formatted(photoKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("COOK_HISTORY_PHOTO_ALREADY_USED"));

        assertThat(cookHistoryRepository.count()).isOne();
    }

    @Test
    @DisplayName("photo_key UNIQUE 위반도 409 COOK_HISTORY_PHOTO_ALREADY_USED 로 번역된다")
    void translatesPhotoKeyUniqueViolation() throws Exception {
        // 연결은 통과하고 INSERT 만 제약에 걸리게 만든다. UNIQUE 방어선을 지나는 유일한 경로다.
        String photoKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);
        jdbcTemplate.update("insert into cook_history (recipe_id, cooked_at, photo_key) values (?, now(), ?)",
                recipeId, photoKey);

        create("""
                {"photoKey":"%s"}
                """.formatted(photoKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("COOK_HISTORY_PHOTO_ALREADY_USED"));

        // 저장이 실패했으므로 연결도 롤백돼야 한다.
        assertThat(fixtures.isAttached(photoKey)).isFalse();
        assertThat(cookHistoryRepository.count()).isOne();
    }

    @Test
    @DisplayName("없는 레시피면 404 RECIPE_NOT_FOUND 다")
    void rejectsMissingRecipe() throws Exception {
        create(99999999L, "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 사용자의 레시피도 같은 404 RECIPE_NOT_FOUND 다")
    void rejectsOtherUsersRecipe() throws Exception {
        Long otherRecipeId = fixtures.saveRecipe(OTHER_ID);

        create(otherRecipeId, "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));

        assertThat(cookHistoryRepository.count()).isZero();
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/recipes/{recipeId}/cook-histories", recipeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("recipeId 가 숫자가 아니면 400 INVALID_REQUEST_FORMAT 이다")
    void rejectsNonNumericRecipeId() throws Exception {
        mockMvc.perform(post("/api/v1/recipes/{recipeId}/cook-histories", "abc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("본문이 없으면 400 INVALID_REQUEST_FORMAT 이다")
    void rejectsMissingBody() throws Exception {
        mockMvc.perform(post("/api/v1/recipes/{recipeId}/cook-histories", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }
}
