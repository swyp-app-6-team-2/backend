package com.star_pick.starpick.domain.auth.client.google;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.util.Date;
import org.junit.jupiter.api.Test;

class GoogleUserInfoClientTest {
    private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
    private final GoogleUserInfoClient client = new GoogleUserInfoClient(verifier);

    private String token() {
        return Jwts.builder().subject("google-id").issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300000))
                .signWith(Jwts.SIG.HS256.key().build()).compact();
    }

    @Test void malformedInputDoesNotContactVerifier() {
        for (String value : new String[]{null, "", "abc", "e30.e2JhZA.c2ln"}) {
            assertThatThrownBy(() -> client.getUserInfo(value)).isInstanceOf(InvalidSocialTokenException.class);
        }
        verifyNoInteractions(verifier);
    }

    @Test void invalidSignatureIsAuthenticationFailure() throws Exception {
        when(verifier.verify(any(GoogleIdToken.class))).thenReturn(false);
        assertThatThrownBy(() -> client.getUserInfo(token())).isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void missingRequiredTimeClaimsAreAuthenticationFailure() {
        String jwt = Jwts.builder().subject("google-id").signWith(Jwts.SIG.HS256.key().build()).compact();
        assertThatThrownBy(() -> client.getUserInfo(jwt)).isInstanceOf(InvalidSocialTokenException.class);
        verifyNoInteractions(verifier);
    }

    @Test void keyFetchFailureIsServerFailure() throws Exception {
        when(verifier.verify(any(GoogleIdToken.class))).thenThrow(new IOException("key server unavailable"));
        assertThatThrownBy(() -> client.getUserInfo(token())).isInstanceOf(SocialAuthServerException.class);
    }

    @Test void emailIsOptional() throws Exception {
        when(verifier.verify(any(GoogleIdToken.class))).thenReturn(true);
        var info = client.getUserInfo(token());
        assertThat(info.socialUid()).isEqualTo("google-id");
        assertThat(info.email()).isNull();
    }
}
