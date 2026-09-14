package com.star_pick.starpick.domain.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.repository.RefreshTokenRepository;
import com.star_pick.starpick.domain.auth.service.RefreshTokenService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class LogoutApiTest {
    private static final long OWNER = 990001L;
    private static final long OTHER = 990002L;
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired RefreshTokenService tokens;
    @Autowired RefreshTokenRepository repository;
    @Autowired TestFixtures fixtures;
    @Value("${jwt.secret}") String secret;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
    }

    @Test void logoutDeletesOnlyAuthenticatedUsersTokenAndCanBeRepeated() throws Exception {
        var pair = tokens.issueAndStore(OWNER);
        var otherHash = hash(tokens.issueAndStore(OTHER).refreshToken());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + pair.accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("로그아웃되었습니다."))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
        assertThat(repository.findByUserId(OWNER)).isEmpty();
        assertThat(repository.findByUserId(OTHER).orElseThrow().getTokenHash()).isEqualTo(otherHash);
    }

    @Test void missingInvalidExpiredOrWrongTypeTokensCannotLogout() throws Exception {
        var pair = tokens.issueAndStore(OWNER);
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        var expired = new JwtProvider(secret, -1000, 1000, 1000).generateTokens(OWNER).accessToken();
        for (String invalid : List.of("bad", pair.refreshToken(), expired,
                jwt.generateSignupToken(com.star_pick.starpick.domain.user.entity.Provider.APPLE, "id", null))) {
            mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(repository.findByUserId(OWNER).orElseThrow().getTokenHash()).isEqualTo(hash(pair.refreshToken()));
    }

    @Test void accessTokenRemainsValidUntilExpiryByPolicy() throws Exception {
        var pair = tokens.issueAndStore(OWNER);
        tokens.revoke(OWNER);
        mvc.perform(get("/api/v1/__security-probe").header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test void issuingAgainStoresOnlyLatestHash() throws Exception {
        var first = tokens.issueAndStore(OWNER);
        var second = tokens.issueAndStore(OWNER);
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertThat(repository.findByUserId(OWNER).orElseThrow().getTokenHash()).isEqualTo(hash(second.refreshToken()));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test void reloginDoesNotRestoreRevokedTokenValue() throws Exception {
        var first = tokens.issueAndStore(OWNER);
        tokens.revoke(OWNER);
        var second = tokens.issueAndStore(OWNER);
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(repository.findByUserId(OWNER).orElseThrow().getTokenHash()).isNotEqualTo(hash(first.refreshToken()));
    }

    @Test void simultaneousFirstLoginsAllSucceedWithOneStoredRow() throws Exception {
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<String> issuedHashes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<JwtProvider.TokenPair>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    return tokens.issueAndStore(OWNER);
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            for (var future : futures) issuedHashes.add(hash(future.get(20, TimeUnit.SECONDS).refreshToken()));
        }
        assertThat(issuedHashes).doesNotHaveDuplicates();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(issuedHashes).contains(repository.findByUserId(OWNER).orElseThrow().getTokenHash());
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
