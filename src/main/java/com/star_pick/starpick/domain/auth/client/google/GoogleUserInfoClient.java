package com.star_pick.starpick.domain.auth.client.google;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.star_pick.starpick.domain.auth.client.SocialUserInfo;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClient;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import com.star_pick.starpick.domain.user.entity.Provider;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Slf4j
@Component
public class GoogleUserInfoClient implements SocialUserInfoClient {
    private final GoogleIdTokenVerifier verifier;

    public GoogleUserInfoClient(@Value("${google.client-id}") String googleClientId)
            throws GeneralSecurityException, IOException {

        this.verifier = new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();
    }

    @Override
    public Provider getProvider() {
        return Provider.GOOGLE;
    }

    @Override
    public SocialUserInfo getUserInfo(String authToken) {
        try {
            GoogleIdToken idToken = verifier.verify(authToken);

            if (idToken == null) {
                throw new InvalidSocialTokenException();
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            return new SocialUserInfo(payload.getSubject(), payload.getEmail());

        } catch (GeneralSecurityException | IOException e) {
            log.error("구글 사용자 정보 조회 중 오류", e);
            throw new SocialAuthServerException(Provider.GOOGLE);
        }
    }
}
