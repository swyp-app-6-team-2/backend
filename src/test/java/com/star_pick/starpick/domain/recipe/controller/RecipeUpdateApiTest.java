package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.domain.RecipeIngredient;
import com.star_pick.starpick.domain.recipe.domain.RecipeStep;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
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
 * PATCH /api/v1/recipes/{recipeId} 통합 테스트.
 *
 * <p>핵심은 세 가지 의미의 구분이다 — 미전달은 유지, 명시적 null 은 제거, 빈 배열은 전체 삭제.
 */
@IntegrationTest
class RecipeUpdateApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;
    private Long recipeId;

    @BeforeEach
    void setUp() {
        recipeRepository.deleteAll();
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
        recipeId = saveRecipe(OWNER_ID);
    }

    private Long saveRecipe(Long ownerId) {
        Recipe recipe = Recipe.createManual(ownerId, "김치찌개", RecipeCategory.KOREAN, 30, 2, "조금 맵게");
        recipe.replaceIngredients(List.of(
                RecipeIngredient.of("김치", "1/4포기"),
                RecipeIngredient.of("두부", null)));
        recipe.replaceSteps(List.of(RecipeStep.of("물을 끓인다"), RecipeStep.of("김치를 넣는다")));
        return recipeRepository.save(recipe).getId();
    }

    private ResultActions update(Long targetId, String body) throws Exception {
        var request = patch("/api/v1/recipes/{recipeId}", targetId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(body);
        }
        return mockMvc.perform(request);
    }

    private ResultActions read() throws Exception {
        return mockMvc.perform(get("/api/v1/recipes/{recipeId}", recipeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private int ingredientRowCount() {
        return jdbcTemplate.queryForObject("select count(*) from recipe_ingredient", Integer.class);
    }

    @Test
    @DisplayName("전달한 필드만 바뀌고 나머지는 유지된다")
    void updatesOnlyGivenFields() throws Exception {
        update(recipeId, "{\"title\":\"부대찌개\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("레시피가 수정되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        read()
                .andExpect(jsonPath("$.data.title").value("부대찌개"))
                .andExpect(jsonPath("$.data.memo").value("조금 맵게"))
                .andExpect(jsonPath("$.data.cookTimeMinutes").value(30))
                .andExpect(jsonPath("$.data.ingredients.length()").value(2))
                .andExpect(jsonPath("$.data.steps.length()").value(2));
    }

    @Test
    @DisplayName("명시적 null 은 값을 제거하고, 미전달 필드는 그대로 둔다")
    void explicitNullRemovesValue() throws Exception {
        update(recipeId, "{\"memo\":null}").andExpect(status().isOk());

        read()
                .andExpect(jsonPath("$.data.memo").doesNotExist())
                .andExpect(jsonPath("$.data.title").value("김치찌개"))
                .andExpect(jsonPath("$.data.cookTimeMinutes").value(30));
    }

    @Test
    @DisplayName("조리 시간도 null 로 제거된다")
    void cookTimeIsRemovedWithNull() throws Exception {
        update(recipeId, "{\"cookTimeMinutes\":null}").andExpect(status().isOk());

        read().andExpect(jsonPath("$.data.cookTimeMinutes").doesNotExist());
    }

    @Test
    @DisplayName("빈 배열은 전체 삭제다")
    void emptyArrayDeletesAll() throws Exception {
        update(recipeId, "{\"ingredients\":[]}").andExpect(status().isOk());

        read()
                .andExpect(jsonPath("$.data.ingredients.length()").value(0))
                .andExpect(jsonPath("$.data.steps.length()").value(2));
        assertThat(ingredientRowCount()).isZero();
    }

    @Test
    @DisplayName("배열을 전달하면 전체 교체되고 옛 행이 남지 않는다")
    void arrayReplacesAll() throws Exception {
        update(recipeId, """
                {"ingredients":[{"name":"스팸","amountText":"1캔"},{"name":"소시지"}]}
                """).andExpect(status().isOk());

        read()
                .andExpect(jsonPath("$.data.ingredients.length()").value(2))
                .andExpect(jsonPath("$.data.ingredients[0].name").value("스팸"))
                .andExpect(jsonPath("$.data.ingredients[1].name").value("소시지"));

        assertThat(ingredientRowCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "select display_order from recipe_ingredient order by display_order", Integer.class))
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("같은 재료를 그대로 다시 보내면 행을 다시 만들지 않는다")
    void identicalArrayIsNotReplaced() throws Exception {
        List<Long> before = jdbcTemplate.queryForList(
                "select id from recipe_ingredient order by display_order", Long.class);

        update(recipeId, """
                {"ingredients":[{"name":"김치","amountText":"1/4포기"},{"name":"두부"}]}
                """).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForList(
                "select id from recipe_ingredient order by display_order", Long.class))
                .containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("순서만 바뀌어도 교체된다")
    void reorderIsReplaced() throws Exception {
        update(recipeId, """
                {"ingredients":[{"name":"두부"},{"name":"김치","amountText":"1/4포기"}]}
                """).andExpect(status().isOk());

        read()
                .andExpect(jsonPath("$.data.ingredients[0].name").value("두부"))
                .andExpect(jsonPath("$.data.ingredients[1].name").value("김치"));
    }

    @Test
    @DisplayName("필수 필드에 명시적 null 을 보내면 400 이다")
    void rejectsNullOnRequiredField() throws Exception {
        update(recipeId, "{\"title\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("title"))
                .andExpect(jsonPath("$.data.errors[0].reason").value("제목은 비울 수 없습니다."));
    }

    @Test
    @DisplayName("인분 수는 null 로 제거할 수 없다")
    void rejectsNullServings() throws Exception {
        update(recipeId, "{\"servings\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("servings"));
    }

    @Test
    @DisplayName("인분 수가 0 이면 400 이다")
    void rejectsZeroServings() throws Exception {
        update(recipeId, "{\"servings\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[0].field").value("servings"))
                .andExpect(jsonPath("$.data.errors[0].reason").value("인분 수는 1 이상이어야 합니다."));
    }

    @Test
    @DisplayName("배열에 명시적 null 을 보내면 400 이다 — 전체 삭제는 빈 배열로 표현한다")
    void rejectsNullArray() throws Exception {
        update(recipeId, "{\"ingredients\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("ingredients"));
    }

    @Test
    @DisplayName("배열 요소의 검증 실패는 중첩 경로로 알려준다")
    void reportsNestedFieldPath() throws Exception {
        update(recipeId, """
                {"ingredients":[{"name":"스팸"},{"name":""}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[0].field").value("ingredients[1].name"));
    }

    @Test
    @DisplayName("수정에서도 varchar 상한을 넘는 제목은 400 이다")
    void rejectsOversizedTitle() throws Exception {
        update(recipeId, "{\"title\":\"" + "가".repeat(256) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("title"));
    }

    @Test
    @DisplayName("빈 요청 본문은 400 이다")
    void rejectsEmptyBody() throws Exception {
        update(recipeId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("본문이 아예 없어도 같은 400 이다")
    void rejectsMissingBody() throws Exception {
        update(recipeId, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("다른 사용자의 레시피는 404 다")
    void otherUsersRecipeIsNotFound() throws Exception {
        Long otherRecipeId = saveRecipe(OTHER_ID);

        update(otherRecipeId, "{\"title\":\"부대찌개\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("카테고리와 인분 수를 함께 바꿀 수 있다")
    void updatesMultipleFields() throws Exception {
        update(recipeId, "{\"categoryCode\":\"WESTERN\",\"servings\":4}").andExpect(status().isOk());

        read()
                .andExpect(jsonPath("$.data.categoryCode").value("WESTERN"))
                .andExpect(jsonPath("$.data.servings").value(4));
    }
}
