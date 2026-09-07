package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * DELETE /api/v1/recipes/{recipeId} 통합 테스트.
 *
 * <p>삭제는 DB 행과 저장소 파일 양쪽을 지운다. 응답만 보면 둘 다 성공한 것처럼 보이므로
 * 검증은 원시 SQL 행 수와 {@link FakeObjectStorage#contains} 로 한다. 특히 GCS 삭제는 커밋 후
 * {@code afterCommit} 에서 일어나 조용히 사라져도 응답이 200 이다.
 */
@IntegrationTest
class RecipeDeleteApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private FakeObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private ResultActions requestDelete(Long recipeId) throws Exception {
        return mockMvc.perform(delete("/api/v1/recipes/{recipeId}", recipeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private int countWhere(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("소유한 레시피를 삭제하고 200 과 data:null 을 반환한다")
    void deletesOwnedRecipe() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);

        requestDelete(recipeId)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"status":200,"message":"레시피가 삭제되었습니다.","data":null}
                        """, JsonCompareMode.STRICT));

        assertThat(countWhere("select count(*) from recipe where id = ?", recipeId)).isZero();
    }

    @Test
    @DisplayName("재료와 조리 순서도 함께 삭제된다")
    void deletesChildren() throws Exception {
        Long recipeId = fixtures.saveRecipeWithChildren(OWNER_ID);
        assertThat(countWhere("select count(*) from recipe_ingredient where recipe_id = ?", recipeId)).isEqualTo(2);

        requestDelete(recipeId).andExpect(status().isOk());

        assertThat(countWhere("select count(*) from recipe_ingredient where recipe_id = ?", recipeId)).isZero();
        assertThat(countWhere("select count(*) from recipe_step where recipe_id = ?", recipeId)).isZero();
    }

    @Test
    @DisplayName("대표 이미지의 UploadObject 와 저장소 파일이 삭제된다")
    void deletesCoverImage() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);
        String coverKey = fixtures.attachCover(OWNER_ID, recipeId);

        requestDelete(recipeId).andExpect(status().isOk());

        assertThat(countWhere("select count(*) from upload_object where object_key = ?", coverKey)).isZero();
        assertThat(objectStorage.contains(coverKey)).isFalse();
    }

    @Test
    @DisplayName("조리 이력과 완성 사진이 함께 삭제된다")
    void deletesCookHistoriesWithPhotos() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);
        String firstPhoto = fixtures.saveCookHistoryWithPhoto(OWNER_ID, recipeId);
        String secondPhoto = fixtures.saveCookHistoryWithPhoto(OWNER_ID, recipeId);

        requestDelete(recipeId).andExpect(status().isOk());

        assertThat(countWhere("select count(*) from cook_history where recipe_id = ?", recipeId)).isZero();
        assertThat(countWhere("select count(*) from upload_object where object_key in (?, ?)",
                firstPhoto, secondPhoto)).isZero();
        assertThat(objectStorage.contains(firstPhoto)).isFalse();
        assertThat(objectStorage.contains(secondPhoto)).isFalse();
    }

    @Test
    @DisplayName("사진 없는 조리 이력도 삭제된다")
    void deletesCookHistoryWithoutPhoto() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);
        fixtures.saveCookHistory(recipeId);

        requestDelete(recipeId).andExpect(status().isOk());

        assertThat(countWhere("select count(*) from cook_history where recipe_id = ?", recipeId)).isZero();
    }

    @Test
    @DisplayName("같은 사용자의 다른 레시피와 그 이력은 남는다")
    void keepsOtherRecipeOfSameUser() throws Exception {
        Long target = fixtures.saveRecipe(OWNER_ID);
        Long survivor = fixtures.saveRecipeWithChildren(OWNER_ID);
        String survivorCover = fixtures.attachCover(OWNER_ID, survivor);
        String survivorPhoto = fixtures.saveCookHistoryWithPhoto(OWNER_ID, survivor);
        fixtures.saveCookHistoryWithPhoto(OWNER_ID, target);

        requestDelete(target).andExpect(status().isOk());

        assertThat(countWhere("select count(*) from recipe where id = ?", survivor)).isEqualTo(1);
        assertThat(countWhere("select count(*) from cook_history where recipe_id = ?", survivor)).isEqualTo(1);
        assertThat(countWhere("select count(*) from upload_object where object_key in (?, ?)",
                survivorCover, survivorPhoto)).isEqualTo(2);
        assertThat(objectStorage.contains(survivorCover)).isTrue();
        assertThat(objectStorage.contains(survivorPhoto)).isTrue();
    }

    @Test
    @DisplayName("없는 레시피는 404 RECIPE_NOT_FOUND 다")
    void missingRecipeIsNotFound() throws Exception {
        requestDelete(99_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("레시피를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("다른 사용자의 레시피는 404 이고 실제로 남아 있다")
    void otherUsersRecipeIsNotFound() throws Exception {
        Long recipeId = fixtures.saveRecipe(OTHER_ID);

        requestDelete(recipeId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));

        assertThat(countWhere("select count(*) from recipe where id = ?", recipeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("인증 없이 호출하면 401 이다")
    void anonymousIsUnauthorized() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);

        mockMvc.perform(delete("/api/v1/recipes/{recipeId}", recipeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(countWhere("select count(*) from recipe where id = ?", recipeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("숫자가 아닌 recipeId 는 400 INVALID_REQUEST_FORMAT 이다")
    void nonNumericIdIsBadRequest() throws Exception {
        mockMvc.perform(delete("/api/v1/recipes/{recipeId}", "abc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("저장소 삭제가 실패해도 200 이고 DB 삭제는 유지된다")
    void storageFailureKeepsDeletion() throws Exception {
        Long recipeId = fixtures.saveRecipeWithChildren(OWNER_ID);
        fixtures.attachCover(OWNER_ID, recipeId);
        fixtures.saveCookHistoryWithPhoto(OWNER_ID, recipeId);

        objectStorage.startFailing();

        requestDelete(recipeId).andExpect(status().isOk());

        assertThat(countWhere("select count(*) from recipe where id = ?", recipeId)).isZero();
        assertThat(countWhere("select count(*) from cook_history where recipe_id = ?", recipeId)).isZero();
        assertThat(countWhere("select count(*) from upload_object")).isZero();
    }

    @Test
    @DisplayName("커버가 없으면 다른 사용자·다른 용도의 UploadObject 를 건드리지 않는다")
    void doesNotTouchUnrelatedUploadObjects() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);
        String otherUsersKey = fixtures.uploadedKey(OTHER_ID, UploadPurpose.RECIPE_COVER);
        String otherPurposeKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);

        requestDelete(recipeId).andExpect(status().isOk());

        assertThat(countWhere("select count(*) from upload_object where object_key in (?, ?)",
                otherUsersKey, otherPurposeKey)).isEqualTo(2);
        assertThat(objectStorage.contains(otherUsersKey)).isTrue();
        assertThat(objectStorage.contains(otherPurposeKey)).isTrue();
    }
}
