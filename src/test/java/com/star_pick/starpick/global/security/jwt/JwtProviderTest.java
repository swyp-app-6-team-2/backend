package com.star_pick.starpick.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.domain.user.entity.Provider;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** parseAccessToken 단위 테스트. Spring Context 를 띄우지 않는다. */
class JwtProviderTest {

    private static final String SECRET = "starpick-test-only-jwt-secret-key-not-for-any-real-environment";
    private static final String OTHER_SECRET = "starpick-another-secret-key-that-is-long-enough-for-hmac-sha";
    private static final long THIRTY_MINUTES = 1_800_000L;

    private final JwtProvider jwtProvider = new JwtProvider(SECRET, THIRTY_MINUTES, THIRTY_MINUTES, THIRTY_MINUTES);

    @Test
    @DisplayName("P1 발급한 access token 을 파싱하면 userId 를 돌려준다")
    void parseAccessToken_returnsUserId() {
        String accessToken = jwtProvider.generateTokens(42L).accessToken();

        assertThat(jwtProvider.parseAccessToken(accessToken)).isEqualTo(42L);
    }

    @Test
    @DisplayName("P2 refresh token 은 access token 자리에서 거부된다")
    void parseAccessToken_rejectsRefreshToken() {
        String refreshToken = jwtProvider.generateTokens(42L).refreshToken();

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(refreshToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("P3 signup token 은 access token 자리에서 거부된다")
    void parseAccessToken_rejectsSignupToken() {
        String signupToken = jwtProvider.generateSignupToken(Provider.GOOGLE, "social-uid", "user@example.com");

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(signupToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("P4 변조된 토큰은 서명 검증에서 거부된다")
    void parseAccessToken_rejectsTamperedToken() {
        String accessToken = jwtProvider.generateTokens(42L).accessToken();
        String tampered = accessToken.substring(0, accessToken.length() - 1)
                + (accessToken.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("P5 만료된 토큰은 거부된다")
    void parseAccessToken_rejectsExpiredToken() {
        JwtProvider expiringProvider = new JwtProvider(SECRET, -1_000L, THIRTY_MINUTES, THIRTY_MINUTES);
        String expired = expiringProvider.generateTokens(42L).accessToken();

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("P6 다른 secret 으로 서명된 토큰은 거부된다")
    void parseAccessToken_rejectsForeignSignature() {
        JwtProvider otherProvider = new JwtProvider(OTHER_SECRET, THIRTY_MINUTES, THIRTY_MINUTES, THIRTY_MINUTES);
        String foreign = otherProvider.generateTokens(42L).accessToken();

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(foreign))
                .isInstanceOf(JwtException.class);
    }
}
