package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/v1/recipes/{recipeId} 통합 테스트. */
@IntegrationTest
class RecipeQueryApiTest {

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

    private String accessToken;

    @BeforeEach
    void setUp() {
        recipeRepository.deleteAll();
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private org.springframework.test.web.servlet.ResultActions read(Long recipeId) throws Exception {
        return mockMvc.perform(get("/api/v1/recipes/{recipeId}", recipeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    @Test
    @DisplayName("소유한 레시피의 상세를 반환한다")
    void returnsDetail() throws Exception {
        Long recipeId = fixtures.saveRecipeWithChildren(OWNER_ID);

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("레시피를 조회했습니다."))
                .andExpect(jsonPath("$.data.recipeId").value(recipeId))
                .andExpect(jsonPath("$.data.title").value("김치찌개"))
                .andExpect(jsonPath("$.data.categoryCode").value("KOREAN"))
                .andExpect(jsonPath("$.data.cookTimeMinutes").value(30))
                .andExpect(jsonPath("$.data.servings").value(2))
                .andExpect(jsonPath("$.data.memo").value("조금 맵게"))
                .andExpect(jsonPath("$.data.ingredients.length()").value(2))
                .andExpect(jsonPath("$.data.ingredients[0].name").value("김치"))
                .andExpect(jsonPath("$.data.ingredients[0].amountText").value("1/4포기"))
                .andExpect(jsonPath("$.data.ingredients[1].amountText").doesNotExist())
                .andExpect(jsonPath("$.data.steps.length()").value(2))
                .andExpect(jsonPath("$.data.steps[0].content").value("물을 끓인다"));
    }

    @Test
    @DisplayName("값이 없는 단일 필드도 키가 null 로 존재한다")
    void nullableKeysArePresent() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);

        String body = read(recipeId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("\"coverImageUrl\":null")
                .contains("\"source\":null");
    }

    @Test
    @DisplayName("재료와 조리 순서가 없으면 빈 배열이다")
    void emptyChildrenAreEmptyArrays() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients").isArray())
                .andExpect(jsonPath("$.data.ingredients.length()").value(0))
                .andExpect(jsonPath("$.data.steps.length()").value(0));
    }

    @Test
    @DisplayName("내부 식별자와 표시 순서는 노출하지 않는다")
    void doesNotExposeInternals() throws Exception {
        Long recipeId = fixtures.saveRecipeWithChildren(OWNER_ID);

        read(recipeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.ingredients[0].displayOrder").doesNotExist())
                .andExpect(jsonPath("$.data.ingredients[0].ingredientId").doesNotExist())
                .andExpect(jsonPath("$.data.steps[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.steps[0].displayOrder").doesNotExist());
    }

    @Test
    @DisplayName("존재하지 않는 레시피는 404 다")
    void missingRecipeIsNotFound() throws Exception {
        read(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("레시피를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 사용자의 레시피도 똑같이 404 다 — 소유 여부를 노출하지 않는다")
    void otherUsersRecipeIsNotFound() throws Exception {
        Long recipeId = fixtures.saveRecipeWithChildren(OTHER_ID);

        read(recipeId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RECIPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("recipeId 가 숫자가 아니면 400 INVALID_REQUEST_FORMAT 이다")
    void rejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/{recipeId}", "abc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void rejectsAnonymous() throws Exception {
        Long recipeId = fixtures.saveRecipe(OWNER_ID);

        mockMvc.perform(get("/api/v1/recipes/{recipeId}", recipeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }
}
