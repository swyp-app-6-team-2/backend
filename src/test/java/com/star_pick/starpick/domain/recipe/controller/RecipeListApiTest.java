package com.star_pick.starpick.domain.recipe.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /api/v1/recipes 통합 테스트. */
@IntegrationTest
class RecipeListApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;

    @BeforeEach
    void setUp() {
        recipeRepository.deleteAll();
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/recipes" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    /**
     * 제목과 생성 시각을 직접 박은 Recipe. {@code @CreationTimestamp} 에 맡기면 저장 순서가 곧
     * 정렬 순서라는 보장이 없어 정렬 테스트가 흔들린다.
     */
    private Long saveRecipeAt(Long ownerId, String title, String createdAt) {
        Long recipeId = fixtures.saveRecipe(ownerId);
        jdbcTemplate.update(
                "update recipe set title = ?, created_at = ?::timestamp where id = ?",
                title, createdAt, recipeId);
        return recipeId;
    }

    @Test
    @DisplayName("소유한 레시피를 최신순으로 반환한다")
    void returnsOwnRecipesLatestFirst() throws Exception {
        saveRecipeAt(OWNER_ID, "오래된 것", "2026-09-01 10:00:00");
        saveRecipeAt(OWNER_ID, "중간 것", "2026-09-02 10:00:00");
        saveRecipeAt(OWNER_ID, "최신 것", "2026-09-03 10:00:00");

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("레시피 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.recipes.length()").value(3))
                .andExpect(jsonPath("$.data.recipes[0].title").value("최신 것"))
                .andExpect(jsonPath("$.data.recipes[1].title").value("중간 것"))
                .andExpect(jsonPath("$.data.recipes[2].title").value("오래된 것"));
    }

    @Test
    @DisplayName("저장한 레시피가 없으면 빈 배열과 0 을 반환한다")
    void returnsEmptyList() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.recipes").isArray())
                .andExpect(jsonPath("$.data.recipes.length()").value(0));
    }

    @Test
    @DisplayName("카드에 필요한 필드를 반환한다")
    void returnsSummaryFields() throws Exception {
        Long recipeId = fixtures.saveRecipeWithChildren(OWNER_ID);
        fixtures.attachCover(OWNER_ID, recipeId);

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(recipeId))
                .andExpect(jsonPath("$.data.recipes[0].title").value("김치찌개"))
                .andExpect(jsonPath("$.data.recipes[0].categoryCode").value("KOREAN"))
                .andExpect(jsonPath("$.data.recipes[0].coverImageUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames.length()").value(2));
    }

    /**
     * {@code doesNotExist()} 는 값이 명시적 null 이어도 통과하므로 "필드가 없다"를 증명하지 못한다.
     * 키 집합을 통째로 비교해 목록 응답의 필드를 정확히 고정한다.
     */
    @Test
    @DisplayName("목록 응답의 필드는 카드에 필요한 5개뿐이다")
    void exposesOnlySummaryFields() throws Exception {
        Long recipeId = fixtures.saveRecipeWithChildren(OWNER_ID);
        fixtures.attachCover(OWNER_ID, recipeId);

        String body = list("")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.Map<String, Object> first =
                com.jayway.jsonpath.JsonPath.parse(body).read("$.data.recipes[0]");

        org.assertj.core.api.Assertions.assertThat(first).containsOnlyKeys(
                "recipeId", "title", "categoryCode", "coverImageUrl", "ingredientNames");
    }

    @Test
    @DisplayName("재료명은 표시 순서대로 반환한다")
    void returnsIngredientNamesInDisplayOrder() throws Exception {
        fixtures.saveRecipeWithChildren(OWNER_ID);

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames[0]").value("김치"))
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames[1]").value("두부"));
    }

    @Test
    @DisplayName("재료가 없으면 빈 배열이다")
    void returnsEmptyIngredientNames() throws Exception {
        fixtures.saveRecipe(OWNER_ID);

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames").isArray())
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames.length()").value(0));
    }

    /** 값이 없는 단일 필드도 키는 null 로 존재해야 한다(02-0). 키 자체가 빠지면 계약 위반이다. */
    @Test
    @DisplayName("대표 이미지가 없어도 coverImageUrl 키는 null 로 존재한다")
    void keepsCoverImageUrlKeyAsNull() throws Exception {
        fixtures.saveRecipe(OWNER_ID);

        String body = list("")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.Map<String, Object> first =
                com.jayway.jsonpath.JsonPath.parse(body).read("$.data.recipes[0]");

        org.assertj.core.api.Assertions.assertThat(first)
                .containsKey("coverImageUrl")
                .containsEntry("coverImageUrl", null);
    }

    @Test
    @DisplayName("다른 사용자의 레시피는 섞이지 않는다")
    void excludesOtherUsersRecipes() throws Exception {
        saveRecipeAt(OWNER_ID, "내 것", "2026-09-01 10:00:00");
        saveRecipeAt(OTHER_ID, "남의 것", "2026-09-02 10:00:00");

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes.length()").value(1))
                .andExpect(jsonPath("$.data.recipes[0].title").value("내 것"));
    }

    @Test
    @DisplayName("totalCount 는 페이지 크기가 아니라 전체 결과 수다")
    void totalCountIsNotPageSize() throws Exception {
        for (int i = 0; i < 25; i++) {
            saveRecipeAt(OWNER_ID, "레시피 " + i, "2026-09-01 10:00:0" + (i % 10));
        }

        list("?page=0&size=20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(25))
                .andExpect(jsonPath("$.data.recipes.length()").value(20));

        list("?page=1&size=20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(25))
                .andExpect(jsonPath("$.data.recipes.length()").value(5));
    }

    @Test
    @DisplayName("범위를 넘는 페이지는 빈 배열이고 totalCount 는 유지된다")
    void returnsEmptyPageBeyondRange() throws Exception {
        saveRecipeAt(OWNER_ID, "하나", "2026-09-01 10:00:00");

        list("?page=99&size=20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes.length()").value(0));
    }

    @Test
    @DisplayName("sort=OLDEST 는 오래된 순으로 반환한다")
    void sortsOldestFirst() throws Exception {
        saveRecipeAt(OWNER_ID, "오래된 것", "2026-09-01 10:00:00");
        saveRecipeAt(OWNER_ID, "최신 것", "2026-09-03 10:00:00");

        list("?sort=OLDEST")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipes[0].title").value("오래된 것"))
                .andExpect(jsonPath("$.data.recipes[1].title").value("최신 것"));
    }

    /**
     * created_at 이 같은 행이 페이지 경계에 걸릴 때 중복·누락이 없어야 한다. id 를 동률 판정자로
     * 넣지 않으면 PostgreSQL 이 순서를 보장하지 않아 이 테스트가 깨진다.
     */
    @Test
    @DisplayName("생성 시각이 같아도 페이지 경계에서 중복·누락이 없다")
    void paginatesStablyOnTiedTimestamps() throws Exception {
        for (int i = 0; i < 6; i++) {
            saveRecipeAt(OWNER_ID, "동시 " + i, "2026-09-01 10:00:00");
        }

        java.util.List<String> collected = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = list("?page=" + page + "&size=2")
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(body);
            collected.addAll(json.read("$.data.recipes[*].title"));
        }

        org.assertj.core.api.Assertions.assertThat(collected)
                .containsExactlyInAnyOrder("동시 0", "동시 1", "동시 2", "동시 3", "동시 4", "동시 5");
    }

    @Test
    @DisplayName("인증 토큰이 없으면 401 이다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/recipes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("page 가 음수면 400 이다")
    void rejectsNegativePage() throws Exception {
        list("?page=-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("size 가 범위를 벗어나면 400 이다")
    void rejectsSizeOutOfRange() throws Exception {
        list("?size=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));

        list("?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("알 수 없는 sort 값은 400 이다")
    void rejectsUnknownSort() throws Exception {
        list("?sort=BOGUS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("숫자가 아닌 page 는 400 이다")
    void rejectsNonNumericPage() throws Exception {
        list("?page=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }
}
