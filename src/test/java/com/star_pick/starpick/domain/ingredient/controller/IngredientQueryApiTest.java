package com.star_pick.starpick.domain.ingredient.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    @DisplayName("활성 재료 104개를 enum 카테고리 순서와 이름 가나다순으로 반환한다")
    void returnsActiveIngredientsInDisplayOrder() throws Exception {
        read()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("재료 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.ingredients.length()").value(104))
                // 카테고리 경계. 각 카테고리의 첫 항목과 마지막 항목이 가나다순 양끝이다.
                .andExpect(jsonPath("$.data.ingredients[0].name").value("닭가슴살"))
                .andExpect(jsonPath("$.data.ingredients[12].name").value("오리고기"))
                .andExpect(jsonPath("$.data.ingredients[13].name").value("갈치"))
                .andExpect(jsonPath("$.data.ingredients[27].name").value("홍합"))
                .andExpect(jsonPath("$.data.ingredients[28].name").value("가지"))
                .andExpect(jsonPath("$.data.ingredients[54].name").value("홍고추"))
                .andExpect(jsonPath("$.data.ingredients[55].name").value("고추장"))
                .andExpect(jsonPath("$.data.ingredients[80].name").value("후추"))
                .andExpect(jsonPath("$.data.ingredients[81].name").value("가쓰오부시"))
                .andExpect(jsonPath("$.data.ingredients[103].name").value("파스타면"))
                // 초성이 같은 묶음 안에서도 가나다순이다(ㅅ < ㅆ, ㅐ < ㅓ).
                .andExpect(jsonPath("$.data.ingredients[91].name").value("배"))
                .andExpect(jsonPath("$.data.ingredients[92].name").value("버터"))
                .andExpect(jsonPath("$.data.ingredients[96].name").value("식빵"))
                .andExpect(jsonPath("$.data.ingredients[97].name").value("쌀"))
                .andExpect(jsonPath("$.data.ingredients[0].ingredientId").isNumber())
                .andExpect(jsonPath("$.data.ingredients[0].code").value("MET008"))
                .andExpect(jsonPath("$.data.ingredients[0].categoryCode").value("MEAT"))
                .andExpect(jsonPath("$.data.ingredients[0].iconUrl")
                        .value("http://localhost/images/ingredients/chicken.webp"))
                .andExpect(jsonPath("$.data.ingredients[*].iconUrl",
                        everyItem(startsWith("http://localhost/images/ingredients/"))))
                .andExpect(jsonPath("$.data.ingredients[0].active").doesNotExist())
                // 별칭이 있는 항목과 없는 항목을 각각 확인한다.
                .andExpect(jsonPath("$.data.ingredients[4].name").value("돼지고기(삼겹살)"))
                .andExpect(jsonPath("$.data.ingredients[4].aliases",
                        containsInAnyOrder("삼겹살", "돼지고기")))
                .andExpect(jsonPath("$.data.ingredients[0].aliases").isArray())
                .andExpect(jsonPath("$.data.ingredients[0].aliases.length()").value(0));
    }

    @Test
    @DisplayName("비활성 재료는 목록에서 제외한다")
    void excludesInactiveIngredients() throws Exception {
        jdbcTemplate.update("update ingredient set active = false where code = 'MET001'");

        read()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients.length()").value(103))
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

    @Test
    @DisplayName("재료 아이콘은 인증 없이 조회되고 7일 캐시된다")
    void servesIngredientIconPubliclyWithCacheHeader() throws Exception {
        mockMvc.perform(get("/images/ingredients/pork.webp"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        containsString("max-age=604800")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        not(containsString("no-store"))));
    }
}
