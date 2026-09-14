package com.star_pick.starpick.domain.auth.client.apple;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import com.star_pick.starpick.domain.user.entity.Provider;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppleUserInfoClientTest {
    private static final KeyPair KEY = Jwts.SIG.RS256.keyPair().build();
    private final AppleSigningKeyProvider keys = mock(AppleSigningKeyProvider.class);
    private final AppleUserInfoClient client = new AppleUserInfoClient(keys, "our.app");

    @BeforeEach
    void setUp() {
        when(keys.find("key-1")).thenReturn(KEY.getPublic());
    }

    private JwtBuilder token() {
        return Jwts.builder().header().keyId("key-1").and()
                .issuer("https://appleid.apple.com").audience().add("our.app").and()
                .subject("apple-subject").expiration(Date.from(Instant.now().plusSeconds(300)));
    }

    private String sign(JwtBuilder builder) {
        return builder.signWith(KEY.getPrivate(), Jwts.SIG.RS256).compact();
    }

    @Test void acceptsSignedTokenWithoutEmail() {
        var result = client.getUserInfo(sign(token()));
        assertThat(result.socialUid()).isEqualTo("apple-subject");
        assertThat(result.email()).isNull();
    }

    @Test void acceptsOptionalRelayEmail() {
        assertThat(client.getUserInfo(sign(token().claim("email", "relay@privaterelay.appleid.com"))).email())
                .isEqualTo("relay@privaterelay.appleid.com");
    }

    @Test void rejectsWrongIssuer() {
        assertThatThrownBy(() -> client.getUserInfo(sign(token().issuer("https://other.example"))))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void rejectsWrongAudience() {
        assertThatThrownBy(() -> client.getUserInfo(sign(token().audience().clear().add("other.app").and())))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void rejectsExpiredToken() {
        assertThatThrownBy(() -> client.getUserInfo(sign(token().expiration(Date.from(Instant.now().minusSeconds(60))))))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void rejectsMissingExpiration() {
        assertThatThrownBy(() -> client.getUserInfo(sign(token().expiration(null))))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void rejectsMissingSubject() {
        assertThatThrownBy(() -> client.getUserInfo(sign(token().subject(null))))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void rejectsForeignSignature() {
        var otherKey = Jwts.SIG.RS256.keyPair().build();
        assertThatThrownBy(() -> client.getUserInfo(token().signWith(otherKey.getPrivate(), Jwts.SIG.RS256).compact()))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void rejectsOtherAlgorithmBeforeKeyLookup() {
        assertThatThrownBy(() -> client.getUserInfo(token().signWith(Jwts.SIG.HS256.key().build()).compact()))
                .isInstanceOf(InvalidSocialTokenException.class);
        verifyNoInteractions(keys);
    }

    @Test void rejectsMalformedToken() {
        assertThatThrownBy(() -> client.getUserInfo("abc")).isInstanceOf(InvalidSocialTokenException.class);
        verifyNoInteractions(keys);
    }

    @Test void rejectsUnknownKey() {
        when(keys.find("key-1")).thenThrow(new InvalidSocialTokenException());
        assertThatThrownBy(() -> client.getUserInfo(sign(token()))).isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void preservesKeyServerFailure() {
        when(keys.find("key-1")).thenThrow(new SocialAuthServerException(Provider.APPLE));
        assertThatThrownBy(() -> client.getUserInfo(sign(token()))).isInstanceOf(SocialAuthServerException.class);
    }

    @Test void checksNonceWhenPresent() {
        String jwt = sign(token().claim("nonce", "expected-nonce"));
        assertThat(client.getUserInfo(jwt, "expected-nonce").socialUid()).isEqualTo("apple-subject");
        assertThatThrownBy(() -> client.getUserInfo(jwt)).isInstanceOf(InvalidSocialTokenException.class);
        assertThatThrownBy(() -> client.getUserInfo(jwt, "wrong")).isInstanceOf(InvalidSocialTokenException.class);
        assertThatThrownBy(() -> client.getUserInfo(sign(token()), "expected-nonce"))
                .isInstanceOf(InvalidSocialTokenException.class);
    }
}
