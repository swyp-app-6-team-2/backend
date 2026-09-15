package com.star_pick.starpick.domain.user.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.user.service.UserService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class UserIngredientsApiTest {
    private static final long OWNER = 995001L;
    private static final long OTHER = 995002L;
    private static final String PATH = "/api/v1/users/me/ingredients";
    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${jwt.secret}") String secret;
    long scallion;
    long chicken;
    long garlic;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
        scallion = ingredient("대파");
        chicken = ingredient("닭가슴살");
        garlic = ingredient("마늘");
    }

    @AfterEach void restore() {
        fixtures.restoreIngredientActivity();
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private long ingredient(String name) {
        return jdbc.queryForObject("select id from ingredient where name = ?", Long.class, name);
    }

    private String bearer(long userId) { return "Bearer " + jwt.generateTokens(userId).accessToken(); }

    @Test void onlyOwnedIngredientsAreSortedAndUseMasterFields() throws Exception {
        users.addIngredients(OWNER, List.of(garlic, scallion, chicken));
        users.addIngredients(OTHER, List.of(ingredient("양파")));
        var body = mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("내 재료 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.ingredients.length()").value(3))
                .andExpect(jsonPath("$.data.ingredients[0].ingredientId").value(chicken))
                .andExpect(jsonPath("$.data.ingredients[0].categoryCode").value("MEAT"))
                .andExpect(jsonPath("$.data.ingredients[1].name").value("대파"))
                .andExpect(jsonPath("$.data.ingredients[2].name").value("마늘"))
                .andReturn().getResponse().getContentAsString();
        var item = json.readTree(body).get("data").get("ingredients").get(0);
        assertThat(item.get("iconUrl").asText()).endsWith("/images/ingredients/chicken.webp");
    }

    @Test void searchMatchesOnlyOwnedNamesAndTrimsWhitespace() throws Exception {
        users.addIngredients(OWNER, List.of(scallion, chicken));
        users.addIngredients(OTHER, List.of(garlic));
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)).param("searchQuery", "  가슴  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ingredients.length()").value(1))
                .andExpect(jsonPath("$.data.ingredients[0].ingredientId").value(chicken));
        for (String query : List.of("마늘", "없는재료", "%", "_", "' OR 1=1 --")) {
            mvc.perform(get(PATH).header("Authorization", bearer(OWNER)).param("searchQuery", query))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.ingredients").isEmpty());
        }
        for (String query : List.of("", "   ")) {
            mvc.perform(get(PATH).header("Authorization", bearer(OWNER)).param("searchQuery", query))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.ingredients.length()").value(2));
        }
    }

    @Test void emptyOwnerReturnsEmptyArrayNotEntireMaster() throws Exception {
        users.addIngredients(OTHER, List.of(chicken));
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("조회된 재료가 없습니다."))
                .andExpect(jsonPath("$.data.ingredients").isEmpty());
    }

    @Test void inactiveOwnedIngredientRemainsVisible() throws Exception {
        users.addIngredients(OWNER, List.of(chicken));
        jdbc.update("update ingredient set active = false where id = ?", chicken);
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)).param("searchQuery", "닭"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ingredients[0].ingredientId").value(chicken));
    }

    @Test void authenticationAndUserStatusAreEnforced() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        var expired = new JwtProvider(secret, -1000, 1000, 1000).generateTokens(OWNER).accessToken();
        for (String token : List.of("invalid", expired, jwt.generateTokens(OWNER).refreshToken())) {
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get(PATH).header("Authorization", bearer(Long.MAX_VALUE))).andExpect(status().isUnauthorized());
        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER))).andExpect(status().isUnauthorized());
    }

    @Test void getDoesNotChangeOwnership() throws Exception {
        users.addIngredients(OWNER, List.of(chicken));
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(PATH).header("Authorization", bearer(OWNER))).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForList("select ingredient_id from user_ingredient where user_id = ?", Long.class, OWNER))
                .containsExactly(chicken);
    }
}
