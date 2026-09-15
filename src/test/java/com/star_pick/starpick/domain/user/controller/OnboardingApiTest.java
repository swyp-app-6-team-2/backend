package com.star_pick.starpick.domain.user.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.service.RefreshTokenService;
import com.star_pick.starpick.domain.user.service.OnboardingService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OnboardingApiTest {
    private static final long OWNER = 992001L;
    private static final long OTHER = 992002L;
    private static final String PATH = "/api/v1/users/me/onboarding";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired OnboardingService onboarding;
    @Autowired RefreshTokenService tokens;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
        jdbc.update("update users set onboarding_completed_at = null, deleted_at = null where user_id in (?, ?)", OWNER, OTHER);
    }

    @AfterEach void restoreUser() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private String bearer(long id) { return "Bearer " + jwt.generateTokens(id).accessToken(); }

    @Test void readsAndCompletesOnlyAuthenticatedAccount() throws Exception {
        mvc.perform(get(PATH).header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.onboardingRequired").value(true));
        mvc.perform(post(PATH + "/complete").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.onboardingRequired").value(false))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value(org.hamcrest.Matchers.endsWith("Z")));
        var first = onboarding.status(OWNER).onboardingCompletedAt();
        mvc.perform(post(PATH + "/complete").header("Authorization", bearer(OWNER))).andExpect(status().isOk());
        assertThat(onboarding.status(OWNER).onboardingCompletedAt()).isEqualTo(first);
        assertThat(onboarding.status(OTHER).onboardingRequired()).isTrue();
    }

    @Test void unauthenticatedAndWrongTokenTypesCannotReadOrComplete() throws Exception {
        for (var request : java.util.List.of(get(PATH), post(PATH + "/complete"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        String refresh = jwt.generateTokens(OWNER).refreshToken();
        mvc.perform(get(PATH).header("Authorization", "Bearer " + refresh)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/complete").header("Authorization", "Bearer " + refresh)).andExpect(status().isUnauthorized());
        assertThat(onboarding.status(OWNER).onboardingRequired()).isTrue();
    }

    @Test void deletedAndMissingUsersAreRejected() throws Exception {
        jdbc.update("update users set deleted_at = current_timestamp where user_id = ?", OWNER);
        for (long id : new long[]{OWNER, Long.MAX_VALUE}) {
            mvc.perform(get(PATH).header("Authorization", bearer(id))).andExpect(status().isUnauthorized());
            mvc.perform(post(PATH + "/complete").header("Authorization", bearer(id))).andExpect(status().isUnauthorized());
        }
    }

    @Test void completionSurvivesRefreshLogoutAndRelogin() {
        var pair = tokens.issueAndStore(OWNER);
        var completed = onboarding.complete(OWNER);
        tokens.refresh(pair.refreshToken());
        tokens.revoke(OWNER);
        tokens.issueAndStore(OWNER);
        assertThat(onboarding.status(OWNER)).isEqualTo(completed);
    }

    @Test void concurrentCompletionsPreserveOneTimestamp() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<com.star_pick.starpick.domain.user.dto.OnboardingResponse>>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return onboarding.complete(OWNER);
            }));
            start.countDown();
            var first = futures.getFirst().get(20, TimeUnit.SECONDS);
            for (var future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            }
            // PostgreSQL stores microsecond precision.
            assertThat(onboarding.status(OWNER).onboardingCompletedAt())
                    .isEqualTo(first.onboardingCompletedAt().truncatedTo(ChronoUnit.MICROS));
        }
    }
}
