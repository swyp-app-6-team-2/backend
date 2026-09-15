package com.star_pick.starpick.domain.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.repository.RefreshTokenRepository;
import com.star_pick.starpick.domain.auth.service.AuthService;
import com.star_pick.starpick.domain.user.entity.Provider;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class TokenRefreshApiTest {
    private static final long OWNER = 991001L;
    @Autowired MockMvc mvc;
    @Autowired AuthService tokens;
    @Autowired RefreshTokenRepository repository;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean JwtProvider jwt;
    @Value("${jwt.secret}") String secret;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
    }

    @AfterEach void restoreUser() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    @Test void rotatesBothTokensWithoutAuthorizationAndStoresHashAndExactExpiry() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        var data = json.readTree(request(old.refreshToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        String access = data.get("accessToken").asText();
        String refresh = data.get("refreshToken").asText();
        assertThat(access).isNotEqualTo(old.accessToken());
        assertThat(refresh).isNotEqualTo(old.refreshToken());
        assertThat(jwt.parseAccessToken(access)).isEqualTo(OWNER);
        var stored = repository.findByUserId(OWNER).orElseThrow();
        assertThat(stored.getTokenHash()).isEqualTo(hash(refresh)).isNotEqualTo(refresh);
        assertThat(stored.getExpiresAt()).isEqualTo(jwt.parseRefreshToken(refresh).expiresAt());
        rejected(old.refreshToken());
        request(refresh).andExpect(status().isOk());
    }

    @Test void expiredAccessHeaderDoesNotBlockRefresh() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        String expired = new JwtProvider(secret, -1000, 1000, 1000).generateTokens(OWNER).accessToken();
        mvc.perform(post("/api/v1/auth/token/refresh").header("Authorization", "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken", old.refreshToken()))))
                .andExpect(status().isOk());
    }

    @Test void missingBlankAndMalformedRequestsReturn400() throws Exception {
        for (String body : List.of("{}", "{\"refreshToken\":null}", "{\"refreshToken\":\" \"}", "{", "")) {
            mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void invalidExpiredWrongSignatureAndWrongTokenTypesReturn401WithoutRevokingValidToken() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        for (String value : List.of("bad", old.accessToken(),
                new JwtProvider(secret, 1000, -1000, 1000).generateTokens(OWNER).refreshToken(),
                new JwtProvider("a-different-secret-with-at-least-32-characters", 60000, 60000, 60000).generateTokens(OWNER).refreshToken(),
                jwt.generateSignupToken(Provider.APPLE, "social-id", null))) {
            rejected(value);
        }
        assertThat(repository.findByUserId(OWNER).orElseThrow().getTokenHash()).isEqualTo(hash(old.refreshToken()));
    }

    @Test void missingRequiredClaimsAndInvalidSubjectReturn401() throws Exception {
        for (String subject : Arrays.asList(null, "invalid", "-1", "0")) {
            rejected(signed(subject, true, true));
        }
        rejected(signed(Long.toString(OWNER), false, true));
        rejected(signed(Long.toString(OWNER), true, false));
    }

    @Test void unregisteredAndReplacedTokensAreRejectedWithoutDamagingCurrentToken() throws Exception {
        rejected(jwt.generateTokens(OWNER).refreshToken());
        var old = tokens.issueAndStore(OWNER);
        var current = tokens.issueAndStore(OWNER);
        rejected(old.refreshToken());
        rejected(jwt.generateTokens(OWNER).refreshToken());
        assertThat(repository.findByUserId(OWNER).orElseThrow().getTokenHash()).isEqualTo(hash(current.refreshToken()));
    }

    @Test void databaseExpiryIsAlsoEnforced() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        jdbc.update("update refresh_tokens set expires_at = ? where user_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), OWNER);
        rejected(old.refreshToken());
    }

    @Test void missingAndDeletedUsersCannotRefresh() throws Exception {
        rejected(jwt.generateTokens(991099L).refreshToken());
        var old = tokens.issueAndStore(OWNER);
        jdbc.update("update users set deleted_at = ? where user_id = ?", OffsetDateTime.now(ZoneOffset.UTC), OWNER);
        rejected(old.refreshToken());
    }

    @Test void logoutRevokesOriginalAndRotatedTokens() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + old.accessToken()))
                .andExpect(status().isOk());
        rejected(old.refreshToken());
        var loggedIn = tokens.issueAndStore(OWNER);
        var rotated = tokens.refresh(loggedIn.refreshToken());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + rotated.accessToken()))
                .andExpect(status().isOk());
        rejected(rotated.refreshToken());
    }

    @Test void tokenGenerationFailureRollsBackAndPreservesOldToken() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        doThrow(new IllegalStateException("simulated generation failure")).when(jwt).generateTokens(OWNER);
        request(old.refreshToken()).andExpect(status().isInternalServerError());
        assertThat(repository.findByUserId(OWNER).orElseThrow().getTokenHash()).isEqualTo(hash(old.refreshToken()));
        doCallRealMethod().when(jwt).generateTokens(OWNER);
        request(old.refreshToken()).andExpect(status().isOk());
    }

    @Test void simultaneousRefreshRequestsHaveExactlyOneWinner() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        String winner = null;
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<org.springframework.mock.web.MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return request(old.refreshToken()).andReturn().getResponse();
            }));
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            for (var future : futures) {
                var response = future.get(20, TimeUnit.SECONDS);
                statuses.add(response.getStatus());
                if (response.getStatus() == 200) winner = json.readTree(response.getContentAsString()).get("data").get("refreshToken").asText();
            }
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 401, 401, 401, 401, 401);
        assertThat(repository.findByUserId(OWNER).orElseThrow().getTokenHash()).isEqualTo(hash(winner));
    }

    @Test void concurrentLogoutAndRefreshLeaveNoRefreshToken() throws Exception {
        var old = tokens.issueAndStore(OWNER);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refresh = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return request(old.refreshToken()).andReturn().getResponse().getStatus();
            });
            var logout = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                tokens.revoke(OWNER);
                return true;
            });
            start.countDown();
            assertThat(refresh.get(20, TimeUnit.SECONDS)).isIn(200, 401);
            assertThat(logout.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(repository.findByUserId(OWNER)).isEmpty();
        rejected(old.refreshToken());
    }

    private ResultActions request(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", token))));
    }

    private void rejected(String token) throws Exception {
        request(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("REFRESH_TOKEN_INVALID"));
    }

    private String signed(String subject, boolean expiry, boolean id) {
        var builder = Jwts.builder().subject(subject).claim("type", "refresh");
        if (expiry) builder.expiration(new Date(System.currentTimeMillis() + 60000));
        if (id) builder.id(UUID.randomUUID().toString());
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
