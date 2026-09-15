package com.star_pick.starpick.domain.user.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.user.repository.UserIngredientRepository;
import com.star_pick.starpick.domain.user.service.UserIngredientService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AddIngredientsApiTest {
    private static final long OWNER = 994001L;
    private static final long OTHER = 994002L;
    private static final String PATH = "/api/v1/users/me/ingredients";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired UserIngredientRepository repository;
    @Autowired UserIngredientService service;
    List<Long> ids;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
        ids = jdbc.queryForList("select id from ingredient where active = true order by id limit 3", Long.class);
    }

    @AfterEach void restore() {
        fixtures.restoreIngredientActivity();
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private ResultActions request(long user, List<Long> values) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + jwt.generateTokens(user).accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ingredientIds", values))));
    }

    @Test void addsOnlyNewIngredientsAndReturnsMasterFields() throws Exception {
        service.add(OWNER, List.of(ids.get(0)));
        var response = request(OWNER, List.of(ids.get(0), ids.get(1), ids.get(1), ids.get(2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("재료가 등록되었습니다."))
                .andExpect(jsonPath("$.data.ingredients.length()").value(2))
                .andExpect(jsonPath("$.data.ingredients[0].ingredientId").value(ids.get(1)))
                .andExpect(jsonPath("$.data.ingredients[0].name").value(jdbc.queryForObject("select name from ingredient where id = ?", String.class, ids.get(1))))
                .andExpect(jsonPath("$.data.ingredients[0].categoryCode").value("MEAT"))
                .andExpect(jsonPath("$.data.ingredients[0].iconUrl").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).get("data").get("ingredients").get(0).get("iconUrl").asText())
                .endsWith("/images/ingredients/" + jdbc.queryForObject("select icon_key from ingredient where id = ?", String.class, ids.get(1)) + ".webp");
        assertThat(repository.findIngredientIds(OWNER)).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(repository.findIngredientIds(OTHER)).isEmpty();
    }

    @Test void repeatedRegistrationReturnsEmptyAndOtherUserCanOwnSameIngredient() throws Exception {
        request(OWNER, ids).andExpect(status().isOk());
        request(OWNER, ids).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("이미 모두 등록된 재료입니다."))
                .andExpect(jsonPath("$.data.ingredients").isEmpty());
        request(OTHER, ids).andExpect(status().isOk()).andExpect(jsonPath("$.data.ingredients.length()").value(3));
        assertThat(repository.findIngredientIds(OWNER)).hasSize(3);
    }

    @Test void invalidRequestBodiesAreRejected() throws Exception {
        for (String body : List.of("{}", "{\"ingredientIds\":null}", "{\"ingredientIds\":[]}",
                "{\"ingredientIds\":[null]}", "{\"ingredientIds\":[0]}", "{\"ingredientIds\":[-1]}")) {
            mvc.perform(post(PATH).header("Authorization", "Bearer " + jwt.generateTokens(OWNER).accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        }
        mvc.perform(post(PATH).header("Authorization", "Bearer " + jwt.generateTokens(OWNER).accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
        assertThat(repository.findIngredientIds(OWNER)).isEmpty();
    }

    @Test void nonexistentIngredientRejectsWholeBatchAndPreservesExistingRows() throws Exception {
        service.add(OWNER, List.of(ids.get(0)));
        request(OWNER, List.of(ids.get(0), ids.get(1), Long.MAX_VALUE)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("USER_INGREDIENT_INVALID"));
        assertThat(repository.findIngredientIds(OWNER)).containsExactly(ids.get(0));
    }

    @Test void inactiveNewIngredientRejectsBatchButInactiveOwnedIngredientIsIgnored() throws Exception {
        jdbc.update("update ingredient set active = false where id = ?", ids.get(1));
        request(OWNER, ids).andExpect(status().isBadRequest());
        assertThat(repository.findIngredientIds(OWNER)).isEmpty();
        jdbc.update("update ingredient set active = true where id = ?", ids.get(1));
        service.add(OWNER, List.of(ids.get(1)));
        jdbc.update("update ingredient set active = false where id = ?", ids.get(1));
        request(OWNER, ids).andExpect(status().isOk()).andExpect(jsonPath("$.data.ingredients.length()").value(2));
    }

    @Test void rejectsUnauthorizedWrongTokenMissingAndDeletedUsers() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"ingredientIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + jwt.generateTokens(OWNER).refreshToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ingredientIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        request(Long.MAX_VALUE, ids).andExpect(status().isUnauthorized());
        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        request(OWNER, ids).andExpect(status().isUnauthorized());
        assertThat(repository.findIngredientIds(OWNER)).isEmpty();
    }

    @Test void simultaneousRequestsReturnEachIngredientOnlyOnce() throws Exception {
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        var added = new ArrayList<Long>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<List<Long>>>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return service.add(OWNER, ids).ingredients().stream().map(item -> item.ingredientId()).toList();
            }));
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            for (var future : futures) added.addAll(future.get(20, TimeUnit.SECONDS));
        }
        assertThat(added).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(repository.findIngredientIds(OWNER)).containsExactlyInAnyOrderElementsOf(ids);
    }
}
