package com.star_pick.starpick.domain.ingredient.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class IngredientQueryApiTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    private String accessToken;

    @BeforeEach
    void setUp() {
        accessToken = jwtProvider.generateTokens(USER_ID).accessToken();
    }

    @AfterEach
    void restoreIngredientActivity() {
        fixtures.restoreIngredientActivity();
    }

    private ResultActions read() throws Exception {
        return mockMvc.perform(get("/api/v1/ingredients")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    @Test
    @DisplayName("활성 재료 87개를 enum 카테고리 순서와 code 오름차순으로 반환한다")
    void returnsActiveIngredientsInDisplayOrder() throws Exception {
        read()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("재료 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.ingredients.length()").value(87))
                .andExpect(jsonPath("$.data.ingredients[0].code").value("MET001"))
                .andExpect(jsonPath("$.data.ingredients[11].code").value("MET012"))
                .andExpect(jsonPath("$.data.ingredients[12].code").value("SEA001"))
                .andExpect(jsonPath("$.data.ingredients[22].code").value("SEA011"))
                .andExpect(jsonPath("$.data.ingredients[23].code").value("VEG001"))
                .andExpect(jsonPath("$.data.ingredients[46].code").value("VEG024"))
                .andExpect(jsonPath("$.data.ingredients[47].code").value("SAU001"))
                .andExpect(jsonPath("$.data.ingredients[68].code").value("SAU022"))
                .andExpect(jsonPath("$.data.ingredients[69].code").value("ETC001"))
                .andExpect(jsonPath("$.data.ingredients[86].code").value("ETC018"))
                .andExpect(jsonPath("$.data.ingredients[0].ingredientId").isNumber())
                .andExpect(jsonPath("$.data.ingredients[0].name").value("돼지고기(삼겹살)"))
                .andExpect(jsonPath("$.data.ingredients[0].categoryCode").value("MEAT"))
                .andExpect(jsonPath("$.data.ingredients[0].aliases",
                        containsInAnyOrder("삼겹살", "돼지고기")))
                .andExpect(jsonPath("$.data.ingredients[0].active").doesNotExist())
                .andExpect(jsonPath("$.data.ingredients[7].aliases").isArray())
                .andExpect(jsonPath("$.data.ingredients[7].aliases.length()").value(0));
    }

    @Test
    @DisplayName("비활성 재료는 목록에서 제외한다")
    void excludesInactiveIngredients() throws Exception {
        jdbcTemplate.update("update ingredient set active = false where code = 'MET001'");

        read()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients.length()").value(86))
                .andExpect(jsonPath("$.data.ingredients[*].code", not(hasItem("MET001"))));
    }

    @Test
    @DisplayName("활성 재료가 없으면 null 대신 빈 배열을 반환한다")
    void returnsEmptyArrayWhenNoIngredientIsActive() throws Exception {
        jdbcTemplate.update("update ingredient set active = false");

        read()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients").isArray())
                .andExpect(jsonPath("$.data.ingredients.length()").value(0));
    }

    @Test
    @DisplayName("토큰이 없으면 401 AUTHENTICATION_REQUIRED 다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/ingredients"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }
}
