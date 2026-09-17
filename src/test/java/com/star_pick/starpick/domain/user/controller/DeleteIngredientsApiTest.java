package com.star_pick.starpick.domain.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.user.dto.UserIngredientResponse;
import com.star_pick.starpick.domain.user.repository.UserCustomIngredientRepository;
import com.star_pick.starpick.domain.user.repository.UserIngredientRepository;
import com.star_pick.starpick.domain.user.service.UserService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class DeleteIngredientsApiTest {
    private static final long OWNER = 113001L;
    private static final long OTHER = 113002L;
    private static final String PATH = "/api/v1/users/me/ingredients";

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired UserService users;
    @Autowired UserIngredientRepository masterIngredients;
    @Autowired UserCustomIngredientRepository customIngredients;

    private long chicken;
    private long scallion;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
        chicken = fixtures.ingredientId("MET008");
        scallion = fixtures.ingredientId("VEG002");
    }

    @AfterEach
    void restore() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private String bearer(long userId) {
        return "Bearer " + jwt.generateTokens(userId).accessToken();
    }

    private String body(String mode, List<Map<String, Object>> items) throws Exception {
        return json.writeValueAsString(Map.of("mode", mode, "ingredients", items));
    }

    private Map<String, Object> target(String type, long id) {
        return Map.of("type", type, "id", id);
    }

    private UserIngredientResponse custom(String name) {
        return users.addCustomIngredient(OWNER, name);
    }

    @Test
    void selectedDeleteRemovesOnlyOwnedMasterAndCustomItems() throws Exception {
        users.addIngredients(OWNER, List.of(chicken, scallion));
        UserIngredientResponse customToDelete = custom("루꼴라");
        UserIngredientResponse customToKeep = custom("바질");
        UserIngredientResponse otherCustom = users.addCustomIngredient(OTHER, "루꼴라");

        mvc.perform(delete(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SELECTED", List.of(
                                target("MASTER", chicken),
                                target("CUSTOM", customToDelete.customIngredientId()),
                                target("MASTER", Long.MAX_VALUE),
                                target("CUSTOM", otherCustom.customIngredientId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("재료가 삭제되었습니다."))
                .andExpect(jsonPath("$.data.deletedCount").value(2));

        assertThat(masterIngredients.findIngredientIds(OWNER)).containsExactly(scallion);
        assertThat(customIngredients.findByUserId(OWNER)).extracting("id")
                .containsExactly(customToKeep.customIngredientId());
        assertThat(customIngredients.findByUserId(OTHER)).extracting("id")
                .containsExactly(otherCustom.customIngredientId());
    }

    @Test
    void allDeleteRemovesBothKindsAndIsIdempotent() throws Exception {
        users.addIngredients(OWNER, List.of(chicken, scallion));
        custom("루꼴라");
        custom("바질");

        mvc.perform(delete(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(body("ALL", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("재료가 삭제되었습니다."))
                .andExpect(jsonPath("$.data.deletedCount").value(4));
        assertThat(masterIngredients.findIngredientIds(OWNER)).isEmpty();
        assertThat(customIngredients.findByUserId(OWNER)).isEmpty();

        mvc.perform(delete(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(body("ALL", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("삭제된 재료가 없습니다."))
                .andExpect(jsonPath("$.data.deletedCount").value(0));
    }

    @Test
    void selectedDeleteWithNoOwnedTargetsReturnsZero() throws Exception {
        mvc.perform(delete(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SELECTED", List.of(target("MASTER", Long.MAX_VALUE), target("CUSTOM", 999999L)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("삭제된 재료가 없습니다."))
                .andExpect(jsonPath("$.data.deletedCount").value(0));
    }

    @Test
    void validatesModeAndSelectedTargets() throws Exception {
        for (String requestBody : List.of(
                "{}",
                "{\"mode\":\"SELECTED\",\"ingredients\":[]}",
                "{\"mode\":\"ALL\",\"ingredients\":[{\"type\":\"MASTER\",\"id\":1}]}",
                "{\"mode\":\"SELECTED\",\"ingredients\":[{\"type\":\"MASTER\",\"id\":0}]}",
                "{\"mode\":\"SELECTED\",\"ingredients\":[{\"type\":\"UNKNOWN\",\"id\":1}]}")) {
            mvc.perform(delete(PATH).header("Authorization", bearer(OWNER))
                            .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                    .andExpect(status().isBadRequest());
        }
        assertThat(masterIngredients.findIngredientIds(OWNER)).isEmpty();
        assertThat(customIngredients.findByUserId(OWNER)).isEmpty();
    }

    @Test
    void requiresAuthenticationAndRejectsDeletedUser() throws Exception {
        String request = body("ALL", List.of());
        mvc.perform(delete(PATH).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).header("Authorization", bearer(Long.MAX_VALUE))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        mvc.perform(delete(PATH).header("Authorization", bearer(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
    }
}
