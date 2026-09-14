package com.star_pick.starpick.domain.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.client.*;
import com.star_pick.starpick.domain.auth.dto.SignupRequest;
import com.star_pick.starpick.domain.auth.service.SignupService;
import com.star_pick.starpick.domain.auth.exception.SignupException;
import com.star_pick.starpick.domain.user.entity.Provider;
import com.star_pick.starpick.domain.user.repository.*;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SignupApiTest {
    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired UserRepository users;
    @Autowired SocialCredentialRepository credentials;
    @Autowired SignupService signupService;
    @Autowired JsonMapper json;
    @MockitoSpyBean JwtProvider jwt;
    @MockitoBean SocialUserInfoClientResolver resolver;
    @Value("${jwt.secret}") String secret;

    @BeforeEach void setUp() {
        fixtures.reset();
        credentials.deleteAll();
    }

    private String token(Provider provider, String email) {
        return jwt.generateSignupToken(provider, "signup-test-uid", email);
    }

    private Map<String, Object> body(String token) {
        Map<String, Object> result = new HashMap<>();
        result.put("signupToken", token);
        result.put("ageOver14Agreed", true);
        result.put("serviceTermsAgreed", true);
        result.put("privacyAgreed", true);
        result.put("marketingAgreed", false);
        result.put("serviceAgreed", false);
        return result;
    }

    private org.springframework.test.web.servlet.ResultActions send(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @ParameterizedTest @EnumSource(Provider.class)
    void signsUpAllProvidersWithOptionalConsentsOffAndNoEmail(Provider provider) throws Exception {
        long before = users.count();
        String response = send(body(token(provider, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(response).get("data");
        long userId = data.get("userId").asLong();
        assertThat(jwt.parseAccessToken(data.get("accessToken").asText())).isEqualTo(userId);
        var user = users.findById(userId).orElseThrow();
        assertThat(user.getAgeOver14Agreed()).isTrue();
        assertThat(user.getAgeOver14AgreedAt()).isNotNull();
        assertThat(user.isServiceTermsAgreed()).isTrue();
        assertThat(user.isPrivacyAgreed()).isTrue();
        assertThat(user.isMarketingAgreed()).isFalse();
        assertThat(user.isServiceAgreed()).isFalse();
        assertThat(user.getMarketingAgreedAt()).isNull();
        assertThat(user.getServiceAgreedAt()).isNull();
        assertThat(user.getSignupCompletedAt()).isNotNull();
        assertThat(user.getLastLoginProvider()).isEqualTo(provider);
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(credentials.findByProviderAndSocialUid(provider, "signup-test-uid").orElseThrow().getEmail()).isNull();
        assertThat(users.count()).isEqualTo(before + 1);
        verifyNoInteractions(resolver);
    }

    @Test void savesOptionalConsentsAndTokenEmail() throws Exception {
        var request = body(token(Provider.GOOGLE, "optional@example.com"));
        request.put("marketingAgreed", true);
        request.put("serviceAgreed", true);
        String response = send(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = users.findById(json.readTree(response).get("data").get("userId").asLong()).orElseThrow();
        assertThat(user.isMarketingAgreed()).isTrue();
        assertThat(user.isServiceAgreed()).isTrue();
        assertThat(user.getMarketingAgreedAt()).isEqualTo(user.getSignupCompletedAt());
        assertThat(user.getServiceAgreedAt()).isEqualTo(user.getSignupCompletedAt());
        assertThat(credentials.findByProviderAndSocialUid(Provider.GOOGLE, "signup-test-uid").orElseThrow().getEmail())
                .isEqualTo("optional@example.com");
    }

    @ParameterizedTest @ValueSource(strings = {"ageOver14Agreed", "serviceTermsAgreed", "privacyAgreed"})
    void requiredConsentFalseNullOrMissingDoesNotCreateUser(String field) throws Exception {
        long before = users.count();
        var request = body(token(Provider.GOOGLE, null));
        for (Boolean value : Arrays.asList(false, null)) {
            request.put(field, value);
            send(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("필수 약관에 동의해야 합니다."))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
        request.remove(field);
        send(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("필수 약관에 동의해야 합니다."));
        assertThat(users.count()).isEqualTo(before);
        assertThat(credentials.count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"marketingAgreed", "serviceAgreed", "signupToken"})
    void requiredRequestFieldsCannotBeOmittedOrNull(String field) throws Exception {
        var request = body(token(Provider.GOOGLE, null));
        request.remove(field);
        send(request).andExpect(status().isBadRequest());
        request.put(field, null);
        send(request).andExpect(status().isBadRequest());
    }

    @Test void rejectsInvalidExpiredForeignAndWrongTypeTokens() throws Exception {
        long before = users.count();
        var foreign = new JwtProvider("foreign-signing-secret-at-least-32-bytes-long", 1000, 1000, 1000);
        var expired = new JwtProvider(secret, 1000, 1000, -1000);
        var login = jwt.generateTokens(1L);
        for (String invalid : List.of("not-a-token", login.accessToken(), login.refreshToken(),
                foreign.generateSignupToken(Provider.APPLE, "id", null),
                expired.generateSignupToken(Provider.APPLE, "id", null))) {
            send(body(invalid)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("회원가입 인증 정보가 유효하지 않습니다. 다시 로그인해주세요."))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
        assertThat(users.count()).isEqualTo(before);
    }

    @Test void rejectsMissingAndInvalidSignedClaims() throws Exception {
        var key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> valid = Map.of("type", "signup", "provider", "GOOGLE", "socialUid", "id");
        for (String missing : List.of("type", "provider", "socialUid")) {
            var claims = new HashMap<>(valid);
            claims.remove(missing);
            send(body(Jwts.builder().claims(claims).expiration(Date.from(Instant.now().plusSeconds(300)))
                    .signWith(key).compact())).andExpect(status().isUnauthorized());
        }
        send(body(Jwts.builder().claims(valid).signWith(key).compact())).andExpect(status().isUnauthorized());
        for (Object provider : List.of("UNKNOWN", 42)) {
            var claims = new HashMap<>(valid);
            claims.put("provider", provider);
            send(body(Jwts.builder().claims(claims).expiration(Date.from(Instant.now().plusSeconds(300)))
                    .signWith(key).compact())).andExpect(status().isUnauthorized());
        }
    }

    @Test void repeatedSignupCannotChangeOriginalConsentsOrCreateAnotherUser() throws Exception {
        var request = body(token(Provider.KAKAO, null));
        String response = send(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long userId = json.readTree(response).get("data").get("userId").asLong();
        long count = users.count();
        request.put("marketingAgreed", true);
        send(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 가입된 계정입니다. 다시 로그인해주세요."));
        assertThat(users.count()).isEqualTo(count);
        assertThat(credentials.count()).isEqualTo(1);
        assertThat(users.findById(userId).orElseThrow().isMarketingAgreed()).isFalse();
    }

    @Test void sameEmailDoesNotMergeDifferentSocialAccounts() throws Exception {
        long before = users.count();
        send(body(token(Provider.GOOGLE, "shared@example.com"))).andExpect(status().isOk());
        send(body(token(Provider.APPLE, "shared@example.com"))).andExpect(status().isOk());
        assertThat(users.count()).isEqualTo(before + 2);
        assertThat(credentials.count()).isEqualTo(2);
    }

    @Test void identityComesOnlyFromSignedToken() throws Exception {
        var request = body(token(Provider.NAVER, null));
        request.put("provider", "APPLE");
        request.put("socialUid", "forged-id");
        request.put("email", "forged@example.com");
        request.put("userId", 1);
        send(request).andExpect(status().isOk());
        var saved = credentials.findByProviderAndSocialUid(Provider.NAVER, "signup-test-uid").orElseThrow();
        assertThat(saved.getEmail()).isNull();
        assertThat(credentials.findByProviderAndSocialUid(Provider.APPLE, "forged-id")).isEmpty();
    }

    @Test void tokenIssuanceFailureRollsBackBothUserAndCredential() throws Exception {
        var request = body(token(Provider.NAVER, null));
        long before = users.count();
        doThrow(new IllegalStateException("test token failure")).when(jwt).generateTokens(anyLong());
        send(request).andExpect(status().isInternalServerError());
        assertThat(users.count()).isEqualTo(before);
        assertThat(credentials.count()).isZero();
    }

    @Test void concurrentSignupCreatesExactlyOneUser() throws Exception {
        String signupToken = token(Provider.APPLE, null);
        long before = users.count();
        var request = new SignupRequest(signupToken, true, true, true, false, false);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    try {
                        signupService.signup(request);
                        return 200;
                    } catch (SignupException e) {
                        return e.getStatus().value();
                    }
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) statuses.add(f.get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409, 409, 409, 409);
        }
        assertThat(users.count()).isEqualTo(before + 1);
        assertThat(credentials.count()).isEqualTo(1);
    }

    @Test void socialLoginAfterSignupSkipsTerms() throws Exception {
        var client = mock(SocialUserInfoClient.class);
        when(resolver.resolve(Provider.GOOGLE)).thenReturn(client);
        when(client.getUserInfo(anyString(), nullable(String.class))).thenReturn(new SocialUserInfo("signup-test-uid", null));
        String firstLogin = mvc.perform(post("/api/v1/auth/social-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"authToken\":\"provider-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresTermsAgreement").value(true))
                .andReturn().getResponse().getContentAsString();
        String signupToken = json.readTree(firstLogin).get("data").get("signupToken").asText();
        send(body(signupToken)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/social-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"authToken\":\"provider-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresTermsAgreement").value(false))
                .andExpect(jsonPath("$.data.signupToken").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").isString());
    }
}
