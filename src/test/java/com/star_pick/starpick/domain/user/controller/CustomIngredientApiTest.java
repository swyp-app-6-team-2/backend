package com.star_pick.starpick.domain.user.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.user.repository.UserCustomIngredientRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class CustomIngredientApiTest {
    private static final long OWNER = 109001L;
    private static final long OTHER = 109002L;
    private static final String PATH = "/api/v1/users/me/ingredients/custom";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserCustomIngredientRepository repository;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
    }

    @AfterEach void restore() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private String bearer(long userId) { return "Bearer " + jwt.generateTokens(userId).accessToken(); }

    private ResultActions request(long userId, String body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void registersTrimmedNameAndReturnsCustomFields() throws Exception {
        request(OWNER, "{\"name\":\"  루꼴라  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("재료가 등록되었습니다."))
                .andExpect(jsonPath("$.data.ingredientType").value("CUSTOM"))
                .andExpect(jsonPath("$.data.ingredientId").doesNotExist())
                .andExpect(jsonPath("$.data.customIngredientId").isNumber())
                .andExpect(jsonPath("$.data.name").value("루꼴라"))
                .andExpect(jsonPath("$.data.categoryCode").doesNotExist())
                .andExpect(jsonPath("$.data.iconUrl").doesNotExist());
        assertThat(repository.findByUserId(OWNER)).extracting("name").containsExactly("루꼴라");
    }

    @Test void preservesInternalWhitespace() throws Exception {
        request(OWNER, "{\"name\":\" 완두 콩 \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("완두 콩"));
    }

    @Test void allowsDuplicateNameRegistration() throws Exception {
        request(OWNER, "{\"name\":\"루꼴라\"}").andExpect(status().isOk());
        request(OWNER, "{\"name\":\"루꼴라\"}").andExpect(status().isOk());
        assertThat(repository.findByUserId(OWNER)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}"})
    void rejectsInvalidNames(String body) throws Exception {
        request(OWNER, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        assertThat(repository.findByUserId(OWNER)).isEmpty();
    }

    @Test void rejectsNameLongerThan50CharsAfterTrimButAllowsExactly50() throws Exception {
        request(OWNER, "{\"name\":\"" + "가".repeat(51) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        request(OWNER, "{\"name\":\"  " + "가".repeat(50) + "  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("가".repeat(50)));
        assertThat(repository.findByUserId(OWNER)).extracting("name").containsExactly("가".repeat(50));
    }

    @Test void rejectsMalformedRequestBody() throws Exception {
        request(OWNER, "{").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test void isNotVisibleToOtherUser() throws Exception {
        request(OWNER, "{\"name\":\"루꼴라\"}").andExpect(status().isOk());
        assertThat(repository.findByUserId(OTHER)).isEmpty();
    }

    @Test void requiresAuthenticationAndRejectsDeletedUser() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"루꼴라\"}"))
                .andExpect(status().isUnauthorized());
        request(Long.MAX_VALUE, "{\"name\":\"루꼴라\"}").andExpect(status().isUnauthorized());
        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        request(OWNER, "{\"name\":\"루꼴라\"}").andExpect(status().isUnauthorized());
        assertThat(repository.findByUserId(OWNER)).isEmpty();
    }
}
