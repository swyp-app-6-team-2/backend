package com.star_pick.starpick.domain.auth.client.apple;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AppleSigningKeyProviderTest {
    private static final RSAPublicKey KEY = (RSAPublicKey) Jwts.SIG.RS256.keyPair().build().getPublic();
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final Clock clock = mock(Clock.class);
    private final AppleSigningKeyProvider provider = new AppleSigningKeyProvider(builder, clock);

    @BeforeEach void setUp() {
        when(clock.instant()).thenReturn(NOW);
    }

    private String jwks(String kid) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return """
                {"keys":[{"kty":"RSA","kid":"%s","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}
                """.formatted(kid, encoder.encodeToString(KEY.getModulus().toByteArray()),
                encoder.encodeToString(KEY.getPublicExponent().toByteArray()));
    }

    @Test void cachesKeysAndThrottlesUnknownKids() {
        server.expect(requestTo("https://appleid.apple.com/auth/keys"))
                .andRespond(withSuccess(jwks("key-1"), MediaType.APPLICATION_JSON));
        assertThat(provider.find("key-1")).isEqualTo(KEY);
        assertThat(provider.find("key-1")).isEqualTo(KEY);
        assertThatThrownBy(() -> provider.find("unknown-1")).isInstanceOf(InvalidSocialTokenException.class);
        assertThatThrownBy(() -> provider.find("unknown-2")).isInstanceOf(InvalidSocialTokenException.class);
        server.verify();
    }

    @Test void refreshesOnRotatedKeyAfterCooldown() {
        server.expect(anything()).andRespond(withSuccess(jwks("old"), MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(jwks("new"), MediaType.APPLICATION_JSON));
        provider.find("old");
        when(clock.instant()).thenReturn(NOW.plusSeconds(61));
        assertThat(provider.find("new")).isEqualTo(KEY);
        server.verify();
    }

    @Test void refreshesCachedKeyAfterTtl() {
        server.expect(anything()).andRespond(withSuccess(jwks("key-1"), MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(jwks("key-1"), MediaType.APPLICATION_JSON));
        provider.find("key-1");
        when(clock.instant()).thenReturn(NOW.plusSeconds(3601));
        assertThat(provider.find("key-1")).isEqualTo(KEY);
        server.verify();
    }

    @Test void classifiesAndThrottlesServerFailure() {
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> provider.find("key-1")).isInstanceOf(SocialAuthServerException.class);
        assertThatThrownBy(() -> provider.find("key-1")).isInstanceOf(SocialAuthServerException.class);
        server.verify();
    }

    @Test void invalidKeyDocumentIsServerFailure() {
        server.expect(anything()).andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.find("key-1")).isInstanceOf(SocialAuthServerException.class);
    }

    @Test void missingKidDoesNotContactApple() {
        assertThatThrownBy(() -> provider.find(null)).isInstanceOf(InvalidSocialTokenException.class);
        assertThatThrownBy(() -> provider.find(" ")).isInstanceOf(InvalidSocialTokenException.class);
        server.verify();
    }
}
