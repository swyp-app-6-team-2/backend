package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** POST /api/v1/recipes 통합 테스트. */
@IntegrationTest
class RecipeCreateApiTest {

    private static final Long OWNER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

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

    private org.springframework.test.web.servlet.ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("생성에 성공하면 201 과 recipeId 를 준다")
    void createsRecipe() throws Exception {
        create("""
                {"title":"김치찌개","categoryCode":"KOREAN","cookTimeMinutes":30,"servings":2,"memo":"조금 맵게",
                 "ingredients":[{"name":"김치","amountText":"1/4포기"},{"name":"두부"}],
                 "steps":[{"content":"물을 끓인다"},{"content":"김치를 넣는다"},{"content":"두부를 넣는다"}]}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("레시피가 생성되었습니다."))
                .andExpect(jsonPath("$.data.recipeId").isNumber());

        Map<String, Object> saved = jdbcTemplate.queryForMap("select * from recipe");
        assertThat(saved.get("registration_method")).isEqualTo("MANUAL");
        assertThat(saved.get("category_code")).isEqualTo("KOREAN");
        assertThat(saved.get("user_id")).isEqualTo(OWNER_ID);
        assertThat(saved.get("cover_image_key")).isNull();
    }

    @Test
    @DisplayName("표시 순서는 요청 배열 순서대로 0 부터 매겨진다")
    void assignsZeroBasedDisplayOrder() throws Exception {
        create("""
                {"title":"김치찌개","categoryCode":"KOREAN",
                 "ingredients":[{"name":"김치"},{"name":"두부"},{"name":"대파"}],
                 "steps":[{"content":"끓인다"}]}
                """).andExpect(status().isCreated());

        List<Map<String, Object>> ingredients = jdbcTemplate.queryForList(
                "select name, display_order from recipe_ingredient order by display_order");

        assertThat(ingredients).extracting(row -> row.get("name"), row -> row.get("display_order"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("김치", 0),
                        org.assertj.core.groups.Tuple.tuple("두부", 1),
                        org.assertj.core.groups.Tuple.tuple("대파", 2));
    }

    @Test
    @DisplayName("인분 수를 전달하지 않으면 1 로 저장된다")
    void servingsDefaultsToOne() throws Exception {
        create("""
                {"title":"김치찌개","categoryCode":"KOREAN"}
                """).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("select servings from recipe", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("재료와 조리 순서는 없어도 된다")
    void childrenAreOptional() throws Exception {
        create("""
                {"title":"물","categoryCode":"OTHER"}
                """).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("select count(*) from recipe_ingredient", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from recipe_step", Integer.class)).isZero();
    }

    @Test
    @DisplayName("제목이 없으면 400 REQUEST_VALIDATION_FAILED 다")
    void rejectsMissingTitle() throws Exception {
        create("""
                {"categoryCode":"KOREAN"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("title"))
                .andExpect(jsonPath("$.data.errors[0].reason").value("제목은 필수입니다."));
    }

    @Test
    @DisplayName("재료명이 비면 중첩 경로로 알려준다")
    void reportsNestedFieldPath() throws Exception {
        create("""
                {"title":"김치찌개","categoryCode":"KOREAN","ingredients":[{"name":"김치"},{"name":""}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("ingredients[1].name"));
    }

    @Test
    @DisplayName("없는 카테고리 코드는 400 INVALID_REQUEST_FORMAT 이다")
    void rejectsUnknownCategory() throws Exception {
        create("""
                {"title":"김치찌개","categoryCode":"KOREAN2"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("조리 시간이 0 이면 400 이다")
    void rejectsZeroCookTime() throws Exception {
        create("""
                {"title":"김치찌개","categoryCode":"KOREAN","cookTimeMinutes":0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("cookTimeMinutes"));
    }

    @Test
    @DisplayName("varchar 상한을 넘는 제목은 500 이 아니라 400 이다")
    void rejectsOversizedTitle() throws Exception {
        String tooLong = "가".repeat(256);

        create("{\"title\":\"" + tooLong + "\",\"categoryCode\":\"KOREAN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("title"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"김치찌개\",\"categoryCode\":\"KOREAN\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }
}
