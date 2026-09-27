package com.star_pick.starpick.domain.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.client.SocialUserInfoClientResolver;
import com.star_pick.starpick.domain.auth.repository.RefreshTokenRepository;
import com.star_pick.starpick.domain.user.entity.AccountType;
import com.star_pick.starpick.domain.user.repository.ProfileRepository;
import com.star_pick.starpick.domain.user.repository.SocialCredentialRepository;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class GuestApiTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired TestFixtures fixtures;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired SocialCredentialRepository credentials;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean JwtProvider jwt;
    @MockitoBean SocialUserInfoClientResolver resolver;

    @BeforeEach void setUp() {
        fixtures.reset();
    }

    private JsonNode createGuest() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/guest"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("게스트 이용이 시작되었습니다."))
                .andExpect(jsonPath("$.data.accountType").value("GUEST"))
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(10))
                .andExpect(jsonPath("$.data.onboardingRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data");
    }

    private String bearer(JsonNode guest) {
        return "Bearer " + guest.get("accessToken").asText();
    }

    @Test void createsGuestWithoutSocialIdentityOrFabricatedConsent() throws Exception {
        long userCount = users.count();
        long credentialCount = credentials.count();
        var guest = createGuest();
        long id = guest.get("userId").asLong();
        var user = users.findById(id).orElseThrow();
        assertThat(users.count()).isEqualTo(userCount + 1);
        assertThat(user.getAccountType()).isEqualTo(AccountType.GUEST);
        assertThat(user.getSignupCompletedAt()).isNull();
        assertThat(user.getAgeOver14Agreed()).isNull();
        assertThat(user.getAgeOver14AgreedAt()).isNull();
        assertThat(user.isServiceTermsAgreed()).isFalse();
        assertThat(user.isPrivacyAgreed()).isFalse();
        assertThat(user.isMarketingAgreed()).isFalse();
        assertThat(user.isServiceAgreed()).isFalse();
        assertThat(user.getMarketingAgreedAt()).isNull();
        assertThat(user.getServiceAgreedAt()).isNull();
        assertThat(user.getLastLoginProvider()).isNull();
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(user.getRecipeSlotLimit()).isEqualTo(10);
        assertThat(user.getCumulativeRecipeCount()).isZero();
        assertThat(credentials.count()).isEqualTo(credentialCount);
        assertThat(profiles.findByUser_UserId(id).orElseThrow().getNickname()).matches("스타[0-9]{4}");
        assertThat(jwt.parseAccessToken(guest.get("accessToken").asText())).isEqualTo(id);
        var refresh = guest.get("refreshToken").asText();
        var stored = refreshTokens.findByUserId(id).orElseThrow();
        assertThat(stored.getTokenHash()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(refresh.getBytes(StandardCharsets.UTF_8))));
        assertThat(stored.getExpiresAt()).isEqualTo(jwt.parseRefreshToken(refresh).expiresAt());
        verifyNoInteractions(resolver);
    }

    @Test void guestTokenReadsOwnProfileAndEmptyRecipes() throws Exception {
        var guest = createGuest();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(guest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(guest.get("userId").asLong()))
                .andExpect(jsonPath("$.data.accountType").value("GUEST"))
                .andExpect(jsonPath("$.data.remainingRecipeSlots").value(10));
        mvc.perform(get("/api/v1/recipes").header("Authorization", bearer(guest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test void repeatedCreationProducesIsolatedUsers() throws Exception {
        var first = createGuest();
        var second = createGuest();
        long firstId = first.get("userId").asLong();
        assertThat(second.get("userId").asLong()).isNotEqualTo(firstId);
        String created = mvc.perform(post("/api/v1/recipes").header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"게스트 레시피","categoryCode":"KOREAN"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long recipeId = json.readTree(created).get("data").get("recipeId").asLong();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingRecipeSlots").value(9));
        mvc.perform(get("/api/v1/recipes/{id}", recipeId).header("Authorization", bearer(first)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/recipes/{id}", recipeId).header("Authorization", bearer(second)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recipes").header("Authorization", bearer(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test void refreshKeepsGuestIdentityAndRejectsOldRefreshToken() throws Exception {
        var guest = createGuest();
        long userCount = users.count();
        String body = json.writeValueAsString(Map.of("refreshToken", guest.get("refreshToken").asText()));
        String result = mvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var tokens = json.readTree(result).get("data");
        assertThat(jwt.parseAccessToken(tokens.get("accessToken").asText()))
                .isEqualTo(guest.get("userId").asLong());
        assertThat(users.count()).isEqualTo(userCount);
        mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test void tokenFailureRollsBackUserAndProfile() throws Exception {
        long userCount = users.count();
        long profileCount = profiles.count();
        long tokenCount = refreshTokens.count();
        doThrow(new IllegalStateException("test token issuance failure")).when(jwt).generateTokens(anyLong());
        mvc.perform(post("/api/v1/auth/guest"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.code").value("INTERNAL_SERVER_ERROR"));
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(profiles.count()).isEqualTo(profileCount);
        assertThat(refreshTokens.count()).isEqualTo(tokenCount);
    }

    @Test void expiredOrInvalidHeaderDoesNotBlockPublicCreation() throws Exception {
        mvc.perform(post("/api/v1/auth/guest").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isCreated());
    }

    @Test void existingMemberDefaultsToMemberAndRemainsSeparate() throws Exception {
        long memberId = 988901L;
        fixtures.seedUser(memberId);
        var guest = createGuest();
        assertThat(guest.get("userId").asLong()).isNotEqualTo(memberId);
        assertThat(users.findById(memberId).orElseThrow().getAccountType()).isEqualTo(AccountType.MEMBER);
        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + jwt.generateTokens(memberId).accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountType").value("MEMBER"));
    }

    @Test void databaseRejectsInvalidTypeAndSignupCompletionCombination() throws Exception {
        var guest = createGuest();
        long id = guest.get("userId").asLong();
        assertThatThrownBy(() -> jdbc.update("update users set account_type = 'UNKNOWN' where user_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update users set signup_completed_at = now() where user_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update users set account_type = 'MEMBER' where user_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
