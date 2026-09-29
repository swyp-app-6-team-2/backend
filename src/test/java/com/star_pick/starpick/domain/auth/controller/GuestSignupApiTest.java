package com.star_pick.starpick.domain.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.dto.GuestResponse;
import com.star_pick.starpick.domain.auth.dto.SignupRequest;
import com.star_pick.starpick.domain.auth.exception.SignupException;
import com.star_pick.starpick.domain.auth.repository.RefreshTokenRepository;
import com.star_pick.starpick.domain.auth.service.AuthService;
import com.star_pick.starpick.domain.user.entity.AccountType;
import com.star_pick.starpick.domain.user.entity.Provider;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.domain.user.service.UserWithdrawalRecovery;
import com.star_pick.starpick.domain.user.service.UserWithdrawalService;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import com.star_pick.starpick.support.FakeObjectStorage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class GuestSignupApiTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AuthService auth;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserWithdrawalService withdrawals;
    @Autowired FakeObjectStorage storage;
    @Autowired Clock clock;
    @MockitoSpyBean RefreshTokenRepository refreshTokens;
    @Value("${jwt.secret}") String secret;

    @BeforeEach void setUp() { fixtures.reset(); }

    @AfterEach void clean() {
        jdbc.execute("drop table if exists guest_cleanup_failure_guard");
        // fixtures.reset()은 users를 보존하므로 다른 테스트의 복구 작업에 대상을 남기지 않는다.
        jdbc.update("update users set deleted_at = null where account_type = 'GUEST'");
    }

    private SignupRequest request(String socialUid) {
        return new SignupRequest(jwt.generateSignupToken(Provider.GOOGLE, socialUid, null),
                true, true, true, false, false);
    }

    private ResultActions send(Object body, String authorization) throws Exception {
        var call = post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (authorization != null) call.header("Authorization", authorization);
        return mvc.perform(call);
    }

    private String bearer(GuestResponse guest) { return "Bearer " + guest.accessToken(); }

    private JsonNode signup(GuestResponse guest, String uid) throws Exception {
        return json.readTree(send(request(uid), bearer(guest)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private int count(String table, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, id);
    }

    private void assertGuestActive(GuestResponse guest) throws Exception {
        assertThat(users.findById(guest.userId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(refreshTokens.findByUserId(guest.userId())).isPresent();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(guest))).andExpect(status().isOk());
    }

    @Test void createsFreshMemberAndBlocksGuestBeforeDeferredCleanup() throws Exception {
        var guest = auth.createGuest();
        long recipe = fixtures.saveRecipeWithChildren(guest.userId());
        String cover = fixtures.attachCover(guest.userId(), recipe);
        jdbc.update("update users set recipe_slot_limit = 18, cumulative_recipe_count = 5, "
                + "onboarding_completed_at = now() where user_id = ?", guest.userId());
        var other = auth.createGuest();
        fixtures.saveRecipe(other.userId());

        var member = signup(guest, "guest-fresh-member");
        long memberId = member.get("userId").asLong();
        assertThat(memberId).isNotEqualTo(guest.userId());
        var user = users.findById(memberId).orElseThrow();
        assertThat(user.getAccountType()).isEqualTo(AccountType.MEMBER);
        assertThat(user.getRemainingRecipeSlots()).isEqualTo(10);
        assertThat(user.getCumulativeRecipeCount()).isZero();
        assertThat(member.get("onboardingRequired").asBoolean()).isTrue();
        assertThat(count("recipe", memberId)).isZero();
        assertThat(count("recipe", guest.userId())).isEqualTo(1);
        assertThat(users.findById(guest.userId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(refreshTokens.findByUserId(guest.userId())).isEmpty();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(guest)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", guest.refreshToken()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + member.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountType").value("MEMBER"));

        new UserWithdrawalRecovery(jdbc, withdrawals, clock, 1000).recoverBatch();
        assertThat(users.findById(guest.userId())).isEmpty();
        assertThat(count("recipe", guest.userId())).isZero();
        assertThat(count("profiles", guest.userId())).isZero();
        assertThat(storage.contains(cover)).isFalse();
        assertThat(users.findById(memberId)).isPresent();
        assertThat(count("recipe", other.userId())).isEqualTo(1);
        assertGuestActive(other);
        withdrawals.resume(guest.userId()); // 이미 정리된 게스트는 재실행해도 안전하다.
    }

    @Test void noHeaderKeepsOrdinarySignupAndDoesNotTrustBodyUserId() throws Exception {
        var guest = auth.createGuest();
        var body = new HashMap<String, Object>(Map.of("signupToken", request("no-guest-header").signupToken(),
                "ageOver14Agreed", true, "serviceTermsAgreed", true, "privacyAgreed", true,
                "marketingAgreed", false, "serviceAgreed", false));
        body.put("userId", guest.userId());
        body.put("guestUserId", guest.userId());
        send(body, null).andExpect(status().isOk());
        assertGuestActive(guest);
    }

    @Test void onlyAuthenticatedGuestIsMarkedDespiteForgedBodyId() throws Exception {
        var guest = auth.createGuest();
        var other = auth.createGuest();
        var body = new HashMap<String, Object>(Map.of("signupToken", request("forged-guest-id").signupToken(),
                "ageOver14Agreed", true, "serviceTermsAgreed", true, "privacyAgreed", true,
                "marketingAgreed", false, "serviceAgreed", false));
        body.put("userId", other.userId());
        body.put("guestUserId", other.userId());
        send(body, bearer(guest)).andExpect(status().isOk());
        assertThat(users.findById(guest.userId()).orElseThrow().getDeletedAt()).isNotNull();
        assertGuestActive(other);
    }

    @Test void invalidExpiredWrongTypeAndMalformedHeadersNeverFallBackToOrdinarySignup() throws Exception {
        var guest = auth.createGuest();
        long before = users.count();
        var expired = new JwtProvider(secret, -1000, 1000, 1000).generateTokens(guest.userId()).accessToken();
        var foreign = new JwtProvider("foreign-test-signing-secret-at-least-32-bytes", 10000, 10000, 10000)
                .generateTokens(guest.userId()).accessToken();
        for (String header : List.of("", "Basic abc", "Bearer ", "Bearer invalid", "Bearer " + expired,
                "Bearer " + foreign, "Bearer " + guest.refreshToken(), "Bearer " + request("wrong-type").signupToken(),
                "Bearer " + jwt.generateTokens(Long.MAX_VALUE).accessToken())) {
            send(request("invalid-guest-header"), header).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.data.code").value("GUEST_TOKEN_INVALID"));
        }
        assertThat(users.count()).isEqualTo(before);
        assertGuestActive(guest);
    }

    @Test void memberTokenCannotDeleteMemberOrCreateAnotherAccount() throws Exception {
        var member = auth.signup(request("original-member"));
        long before = users.count();
        send(request("member-misuse"), "Bearer " + member.accessToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("GUEST_ACCOUNT_REQUIRED"));
        assertThat(users.count()).isEqualTo(before);
        assertThat(users.findById(member.userId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test void invalidSignupTokenTermsAndExistingSocialAccountKeepGuest() throws Exception {
        var guest = auth.createGuest();
        fixtures.saveRecipe(guest.userId());
        auth.signup(request("already-member"));
        long before = users.count();
        send(new SignupRequest("invalid", true, true, true, false, false), bearer(guest))
                .andExpect(status().isUnauthorized());
        send(new SignupRequest(request("terms").signupToken(), false, true, true, false, false), bearer(guest))
                .andExpect(status().isBadRequest());
        send(request("already-member"), bearer(guest)).andExpect(status().isConflict());
        assertThat(users.count()).isEqualTo(before);
        assertThat(count("recipe", guest.userId())).isEqualTo(1);
        assertGuestActive(guest);
    }

    @Test void failureAfterMarkingRollsBackMemberGuestStateAndTokenRevocation() throws Exception {
        var guest = auth.createGuest();
        long before = users.count();
        long profilesBefore = jdbc.queryForObject("select count(*) from profiles", Long.class);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("test failure after token revocation");
        }).when(refreshTokens).deleteByUserId(guest.userId());
        send(request("rollback-member"), bearer(guest)).andExpect(status().isInternalServerError());
        assertThat(users.count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from profiles", Long.class)).isEqualTo(profilesBefore);
        assertThat(jdbc.queryForObject("select count(*) from social_credentials where social_uid = 'rollback-member'", Long.class)).isZero();
        assertGuestActive(guest);
    }

    @Test void cleanupFailureKeepsNewMemberAndRestartedRecoveryFindsGuest() throws Exception {
        var guest = auth.createGuest();
        fixtures.saveRecipe(guest.userId());
        jdbc.execute("create table guest_cleanup_failure_guard (user_id bigint references users(user_id))");
        jdbc.update("insert into guest_cleanup_failure_guard values (?)", guest.userId());
        var member = signup(guest, "cleanup-failure-member");
        var markedAt = users.findById(guest.userId()).orElseThrow().getDeletedAt();

        new UserWithdrawalRecovery(jdbc, withdrawals, clock, 1000).recoverBatch();
        assertThat(count("recipe", guest.userId())).isZero(); // 앞 단계는 이미 커밋됐다.
        assertThat(users.findById(guest.userId()).orElseThrow().getDeletedAt()).isEqualTo(markedAt);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + member.get("accessToken").asText()))
                .andExpect(status().isOk());
        send(request("reuse-marked-guest"), bearer(guest)).andExpect(status().isUnauthorized());

        jdbc.update("delete from guest_cleanup_failure_guard");
        // 새 인스턴스에는 이전 커서/이벤트가 없다. DB 상태만으로 정리를 재개한다.
        new UserWithdrawalRecovery(jdbc, withdrawals, clock, 1000).recoverBatch();
        assertThat(users.findById(guest.userId())).isEmpty();
        assertThat(users.findById(member.get("userId").asLong())).isPresent();
        send(request("reuse-deleted-guest"), bearer(guest)).andExpect(status().isUnauthorized());
    }

    @Test void concurrentSignupsWithDifferentSocialAccountsConsumeGuestOnce() throws Exception {
        var guest = auth.createGuest();
        var requests = List.of(request("concurrent-first"), request("concurrent-second"));
        long before = users.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (var request : requests) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    try { auth.signup(request, bearer(guest)); return 200; }
                    catch (BusinessException e) { return e.getErrorCode().getStatus().value(); }
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        }
        assertThat(users.count()).isEqualTo(before + 1);
        assertThat(users.findById(guest.userId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test void twoGuestsCompetingForSameSocialAccountKeepLosingGuestActive() throws Exception {
        var guests = List.of(auth.createGuest(), auth.createGuest());
        var request = request("shared-social-identity");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (var guest : guests) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    try { auth.signup(request, bearer(guest)); return 200; }
                    catch (SignupException e) { return e.getStatus().value(); }
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var statuses = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertGuestActive(guests.get(statuses.indexOf(409)));
        }
    }

    @Test void swaggerDocumentsOptionalBearerAndGuestErrors() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.security[1]").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses['403'].description")
                        .value(org.hamcrest.Matchers.containsString("GUEST_ACCOUNT_REQUIRED")));
    }
}
