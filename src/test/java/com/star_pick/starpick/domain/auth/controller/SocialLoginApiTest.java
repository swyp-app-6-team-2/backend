package com.star_pick.starpick.domain.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.client.*;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import com.star_pick.starpick.domain.user.entity.*;
import com.star_pick.starpick.domain.user.repository.*;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
class SocialLoginApiTest {
    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired SocialCredentialRepository credentials;
    @Autowired UserRepository users;
    @MockitoBean SocialUserInfoClientResolver resolver;
    private SocialUserInfoClient client;

    @BeforeEach void setUp() {
        fixtures.reset();
        credentials.deleteAll();
        client = mock(SocialUserInfoClient.class);
        when(resolver.resolve(any())).thenReturn(client);
        when(client.getUserInfo(anyString(), nullable(String.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new SocialUserInfo("social-uid", null);
        });
    }

    @Test void allProvidersIssueSignupTokenWithoutCreatingUser() throws Exception {
        long before = users.count();
        for (Provider provider : Provider.values()) {
            mvc.perform(post("/api/v1/auth/social-login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"" + provider + "\",\"authToken\":\"token\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.requiresTermsAgreement").value(true))
                    .andExpect(jsonPath("$.data.signupToken").isString())
                    .andExpect(jsonPath("$.data.accessToken").doesNotExist());
            verify(resolver).resolve(provider);
        }
        assertThat(users.count()).isEqualTo(before);
        assertThat(credentials.count()).isZero();
    }

    @Test void existingUserLogsInAndPersistsLastLoginWithNullEmail() throws Exception {
        fixtures.seedUser(88001L);
        credentials.saveAndFlush(SocialCredential.builder().user(users.findById(88001L).orElseThrow())
                .provider(Provider.APPLE).socialUid("social-uid").email(null).build());
        mvc.perform(post("/api/v1/auth/social-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"apple\",\"authToken\":\"token\",\"nonce\":\"test-nonce\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresTermsAgreement").value(false))
                .andExpect(jsonPath("$.data.userId").value(88001))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.signupToken").doesNotExist());
        var user = users.findById(88001L).orElseThrow();
        assertThat(user.getLastLoginProvider()).isEqualTo(Provider.APPLE);
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(client).getUserInfo("token", "test-nonce");
    }

    @Test void missingOrBlankFieldsAreBadRequests() throws Exception {
        for (String body : new String[]{"{}", "{\"provider\":null,\"authToken\":\"token\"}",
                "{\"provider\":\"GOOGLE\"}", "{\"provider\":\" \",\"authToken\":\"token\"}",
                "{\"provider\":\"GOOGLE\",\"authToken\":\" \"}",
                "{\"provider\":\"UNKNOWN\",\"authToken\":\"token\"}"}) {
            mvc.perform(post("/api/v1/auth/social-login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        }
        verifyNoInteractions(resolver);
    }

    @Test void preservesAuthenticationAndUpstreamStatusCodes() throws Exception {
        when(client.getUserInfo(anyString(), nullable(String.class))).thenThrow(new InvalidSocialTokenException());
        mvc.perform(post("/api/v1/auth/social-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"authToken\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
        doThrow(new SocialAuthServerException(Provider.GOOGLE)).when(client).getUserInfo(anyString(), nullable(String.class));
        mvc.perform(post("/api/v1/auth/social-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"authToken\":\"token\"}"))
                .andExpect(status().isBadGateway());
    }
}
