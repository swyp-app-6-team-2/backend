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
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Slf4j
@Component
public class GoogleUserInfoClient implements SocialUserInfoClient {
    private final GoogleIdTokenVerifier verifier;

    @Autowired
    public GoogleUserInfoClient(@Value("${google.client-id}") String googleClientId)
            throws GeneralSecurityException, IOException {

        this.verifier = new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();
    }

    GoogleUserInfoClient(GoogleIdTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Provider getProvider() {
        return Provider.GOOGLE;
    }

    @Override
    public SocialUserInfo getUserInfo(String authToken) {
        GoogleIdToken idToken;
        // 파싱 IOException은 잘못된 입력이며, 공개 키 조회 IOException과 구분해야 한다.
        try {
            if (authToken == null || authToken.isBlank()) {
                throw new InvalidSocialTokenException();
            }
            idToken = GoogleIdToken.parse(GsonFactory.getDefaultInstance(), authToken);
            GoogleIdToken.Payload payload = idToken.getPayload();
            if (payload.getSubject() == null || payload.getSubject().isBlank()
                    || payload.getExpirationTimeSeconds() == null || payload.getIssuedAtTimeSeconds() == null) {
                throw new InvalidSocialTokenException();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidSocialTokenException();
        }

        try {
            if (!verifier.verify(idToken)) {
                throw new InvalidSocialTokenException();
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            return new SocialUserInfo(payload.getSubject(), payload.getEmail());

        } catch (IllegalArgumentException e) {
            throw new InvalidSocialTokenException();
        } catch (GeneralSecurityException | IOException e) {
            log.error("구글 사용자 정보 조회 중 오류", e);
            throw new SocialAuthServerException(Provider.GOOGLE);
        }
    }
}
