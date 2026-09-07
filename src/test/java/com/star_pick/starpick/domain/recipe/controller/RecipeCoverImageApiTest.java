package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.repository.UploadObjectRepository;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Recipe 대표 이미지 연결 통합 테스트.
 *
 * <p>생성·조회·수정에 걸친 하나의 기능이라 엔드포인트별 테스트에 흩지 않고 여기 모았다.
 * 세 곳 모두 "업로드까지 마친 Key" 픽스처가 필요한데 그것을 복제하지 않기 위함이다.
 */
@IntegrationTest
class RecipeCoverImageApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private UploadObjectRepository uploadObjectRepository;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private FakeObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;

    @BeforeEach
    void setUp() {
        recipeRepository.deleteAll();
        uploadObjectRepository.deleteAll();
        objectStorage.clear();
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private String uploadedKey(Long userId, UploadPurpose purpose) {
        String objectKey = uploadService.issueUploadUrl(userId, purpose, "image/jpeg").objectKey();
        objectStorage.putObject(objectKey);
        return objectKey;
    }

    private String uploadedCoverKey() {
        return uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);
    }

    private ResultActions create(String coverImageKeyJson) throws Exception {
        return mockMvc.perform(post("/api/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"김치찌개","categoryCode":"KOREAN","servings":2%s}
                        """.formatted(coverImageKeyJson)));
    }

    private ResultActions update(Long recipeId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/recipes/{recipeId}", recipeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Long recipeWithAttachedCover(String objectKey) {
        uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);
        return saveRecipeWithCover(objectKey);
    }

    private Long saveRecipeWithCover(String coverImageKey) {
        Recipe recipe = Recipe.createManual(OWNER_ID, "김치찌개", RecipeCategory.KOREAN, 30, 2, null);
        recipe.changeCoverImage(coverImageKey);
        return recipeRepository.save(recipe).getId();
    }

    private String storedCoverKey(Long recipeId) {
        return jdbcTemplate.queryForObject(
                "select cover_image_key from recipe where id = ?", String.class, recipeId);
    }

    private boolean isAttached(String objectKey) {
        return uploadObjectRepository.findById(objectKey).orElseThrow().isAttached();
    }

    // ---------- 생성 ----------

    @Test
    @DisplayName("업로드까지 마친 Key 로 생성하면 연결된다")
    void createsWithCover() throws Exception {
        String objectKey = uploadedCoverKey();

        create(",\"coverImageKey\":\"%s\"".formatted(objectKey))
                .andExpect(status().isCreated());

        Long recipeId = jdbcTemplate.queryForObject("select id from recipe", Long.class);
        assertThat(storedCoverKey(recipeId)).isEqualTo(objectKey);
        assertThat(isAttached(objectKey)).isTrue();
    }

    @Test
    @DisplayName("대표 이미지 없이도 생성된다")
    void createsWithoutCover() throws Exception {
        create("").andExpect(status().isCreated());

        Long recipeId = jdbcTemplate.queryForObject("select id from recipe", Long.class);
        assertThat(storedCoverKey(recipeId)).isNull();
    }

    @Test
    @DisplayName("발급만 받고 올리지 않은 Key 는 400 이다")
    void rejectsKeyThatWasNeverUploaded() throws Exception {
        String objectKey = uploadService
                .issueUploadUrl(OWNER_ID, UploadPurpose.RECIPE_COVER, "image/jpeg")
                .objectKey();

        create(",\"coverImageKey\":\"%s\"".formatted(objectKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("RECIPE_COVER_INVALID"));
    }

    @Test
    @DisplayName("이미 쓰인 Key 는 409 다")
    void rejectsAlreadyUsedKey() throws Exception {
        String objectKey = uploadedCoverKey();
        create(",\"coverImageKey\":\"%s\"".formatted(objectKey)).andExpect(status().isCreated());

        create(",\"coverImageKey\":\"%s\"".formatted(objectKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("RECIPE_COVER_ALREADY_USED"));
    }

    @Test
    @DisplayName("연결에 실패하면 레시피도 만들어지지 않는다")
    void rollsBackRecipeWhenCoverFails() throws Exception {
        create(",\"coverImageKey\":\"recipe-covers/1/unknown.jpg\"")
                .andExpect(status().isBadRequest());

        assertThat(recipeRepository.count()).isZero();
    }

    // ---------- 조회 ----------

    @Test
    @DisplayName("대표 이미지가 있으면 조회 URL 이 채워진다")
    void returnsViewUrl() throws Exception {
        String objectKey = uploadedCoverKey();
        Long recipeId = saveRecipeWithCover(objectKey);

        mockMvc.perform(get("/api/v1/recipes/{recipeId}", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverImageUrl")
                        .value(FakeObjectStorage.VIEW_URL_PREFIX + objectKey));
    }

    // ---------- 수정 ----------

    @Test
    @DisplayName("coverImageKey 를 보내지 않으면 대표 이미지가 유지된다")
    void keepsCoverWhenNotSent() throws Exception {
        String objectKey = uploadedCoverKey();
        Long recipeId = recipeWithAttachedCover(objectKey);

        update(recipeId, """
                {"title":"제목만 수정"}
                """).andExpect(status().isOk());

        assertThat(storedCoverKey(recipeId)).isEqualTo(objectKey);
    }

    @Test
    @DisplayName("같은 Key 를 다시 보내도 409 가 아니라 정상 수정이다")
    void acceptsSameKeyAgain() throws Exception {
        // 수정 화면이 폼 전체를 다시 보내면서 바뀌지 않은 Key 를 그대로 싣는 흔한 경우다.
        // 그대로 연결을 요청하면 "이미 연결됨"으로 판정되어 정상 수정이 실패한다.
        String objectKey = uploadedCoverKey();
        Long recipeId = recipeWithAttachedCover(objectKey);

        update(recipeId, """
                {"title":"제목 변경","coverImageKey":"%s"}
                """.formatted(objectKey)).andExpect(status().isOk());

        assertThat(storedCoverKey(recipeId)).isEqualTo(objectKey);
        assertThat(uploadObjectRepository.findById(objectKey)).isPresent();
    }

    @Test
    @DisplayName("다른 Key 로 교체하면 이전 UploadObject 와 저장소 파일이 정리된다")
    void replacesCover() throws Exception {
        String oldKey = uploadedCoverKey();
        Long recipeId = recipeWithAttachedCover(oldKey);
        String newKey = uploadedCoverKey();

        update(recipeId, """
                {"coverImageKey":"%s"}
                """.formatted(newKey)).andExpect(status().isOk());

        assertThat(storedCoverKey(recipeId)).isEqualTo(newKey);
        assertThat(isAttached(newKey)).isTrue();
        assertThat(uploadObjectRepository.findById(oldKey)).isEmpty();
        // 커밋 후 삭제 등록(afterCommit)이 실제로 실행됐는지 확인한다.
        assertThat(objectStorage.contains(oldKey)).isFalse();
    }

    @Test
    @DisplayName("null 을 보내면 대표 이미지가 제거된다")
    void removesCover() throws Exception {
        String objectKey = uploadedCoverKey();
        Long recipeId = recipeWithAttachedCover(objectKey);

        update(recipeId, """
                {"coverImageKey":null}
                """).andExpect(status().isOk());

        assertThat(storedCoverKey(recipeId)).isNull();
        assertThat(uploadObjectRepository.findById(objectKey)).isEmpty();
        assertThat(objectStorage.contains(objectKey)).isFalse();
    }

    @Test
    @DisplayName("대표 이미지와 다른 필드를 함께 보내면 둘 다 반영된다")
    void appliesCoverAndOtherFieldsTogether() throws Exception {
        // 회귀 방지: 연결용 벌크 UPDATE 에 clearAutomatically 를 붙이면 영속성 컨텍스트가
        // 비워져, 앞서 적용한 제목·재료 변경이 flush 되지 못하고 사라진다. 그런데도 응답은
        // 200 이라 조용히 깨진다. 이 테스트가 그 회귀를 잡는다.
        String objectKey = uploadedCoverKey();
        Long recipeId = saveRecipeWithCover(null);

        update(recipeId, """
                {"title":"바뀐 제목","memo":"바뀐 메모","coverImageKey":"%s",
                 "ingredients":[{"name":"두부","amountText":"1모"}]}
                """.formatted(objectKey)).andExpect(status().isOk());

        assertThat(storedCoverKey(recipeId)).isEqualTo(objectKey);
        assertThat(jdbcTemplate.queryForObject(
                "select title from recipe where id = ?", String.class, recipeId))
                .isEqualTo("바뀐 제목");
        assertThat(jdbcTemplate.queryForObject(
                "select memo from recipe where id = ?", String.class, recipeId))
                .isEqualTo("바뀐 메모");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from recipe_ingredient where recipe_id = ?", Integer.class, recipeId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("수정에서도 남의 Key 는 400 이고 기존 대표 이미지는 그대로다")
    void rejectsOtherUsersKeyOnUpdate() throws Exception {
        String objectKey = uploadedCoverKey();
        Long recipeId = recipeWithAttachedCover(objectKey);
        String othersKey = uploadedKey(OTHER_ID, UploadPurpose.RECIPE_COVER);

        update(recipeId, """
                {"coverImageKey":"%s"}
                """.formatted(othersKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("RECIPE_COVER_INVALID"));

        assertThat(storedCoverKey(recipeId)).isEqualTo(objectKey);
    }
}
