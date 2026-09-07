package com.star_pick.starpick.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security Filter Chain 통합 테스트.
 *
 * <p>검증 대상은 "필터 체인이 인증을 어떻게 판정하는가"이지 특정 업무 API 가 아니다. 그래서
 * 보호 대상 namespace 안에 있으면서 어떤 Controller 에도 매핑되지 않는 경로를 쓴다.
 * 인증 성공은 "401 이 아니라 404 가 나온다"로 확인한다 — 필터를 통과해 라우팅까지 갔다는 뜻이다.
 *
 * <p>실제 업무 endpoint 를 쓰면 그 endpoint 의 404 본문 계약이 바뀔 때마다 인증 테스트가 함께
 * 깨진다. 예컨대 {@code /api/v1/recipes/1} 은 Recipe 조회가 생기는 순간
 * {@code 404 + data:null} 이 아니라 {@code 404 + RECIPE_NOT_FOUND} 를 돌려준다.
 * <b>이 경로에 Controller 를 매핑하지 말 것.</b>
 */
@IntegrationTest
class JwtAuthenticationTest {

    private static final String PROTECTED_PATH = "/api/v1/__security-probe";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Value("${jwt.secret}")
    private String secret;

    private String validAccessToken() {
        return jwtProvider.generateTokens(1L).accessToken();
    }

    private String expiredAccessToken() {
        return new JwtProvider(secret, -1_000L, 1_000L, 1_000L).generateTokens(1L).accessToken();
    }

    @Test
    @DisplayName("A1 토큰이 없으면 401 AUTHENTICATION_REQUIRED 를 Envelope 로 반환한다")
    void noToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content -> assertThat(content.getResponse().getContentType())
                        .startsWith(MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A2 해석할 수 없는 토큰은 401 이다")
    void garbageToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A3 Bearer prefix 없이 토큰만 보내면 401 이다")
    void missingBearerPrefix() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, validAccessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A4 scheme 은 대소문자를 구분하지 않는다")
    void lowercaseBearerScheme() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "bearer " + validAccessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A5 만료된 토큰은 401 이다")
    void expiredToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredAccessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A6 refresh token 을 access 자리에 보내면 401 이다")
    void refreshTokenRejected() throws Exception {
        String refreshToken = jwtProvider.generateTokens(1L).refreshToken();

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A7 유효한 토큰은 필터를 통과한다 — 401 이 아니라 404 가 나온다")
    void validTokenPassesFilter() throws Exception {
        String body = mockMvc.perform(get(PROTECTED_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validAccessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("A8 permitAll 경로는 만료된 토큰이 붙어도 정상 처리된다")
    void permitAllIgnoresBadToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredAccessToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A9 지원하지 않는 HTTP method 는 405 Envelope 이고 code 가 없다")
    void methodNotAllowed() throws Exception {
        String body = mockMvc.perform(get("/api/v1/auth/social-login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("A10 존재하지 않는 경로도 공통 Envelope 를 유지한다")
    void unknownPath() throws Exception {
        String body = mockMvc.perform(get("/api/v1/auth/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"data\":null");
    }
}
