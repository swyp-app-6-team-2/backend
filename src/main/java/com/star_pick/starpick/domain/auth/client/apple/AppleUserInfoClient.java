package com.star_pick.starpick.domain.auth.client.apple;

import com.star_pick.starpick.domain.auth.client.SocialUserInfo;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClient;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.user.entity.Provider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppleUserInfoClient implements SocialUserInfoClient {
    private final JwtParser parser;

    public AppleUserInfoClient(AppleSigningKeyProvider signingKeys,
                               @Value("${apple.client-id}") String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("apple.client-id는 필수입니다.");
        }
        parser = Jwts.parser()
                .keyLocator(header -> {
                    if (!(header instanceof JwsHeader jws) || !"RS256".equals(jws.getAlgorithm())) {
                        throw new InvalidSocialTokenException();
                    }
                    return signingKeys.find(jws.getKeyId());
                })
                .requireIssuer("https://appleid.apple.com")
                .requireAudience(clientId)
                .build();
    }

    @Override
    public Provider getProvider() {
        return Provider.APPLE;
    }

    @Override
    public SocialUserInfo getUserInfo(String authToken) {
        return getUserInfo(authToken, null);
    }

    @Override
    public SocialUserInfo getUserInfo(String authToken, String nonce) {
        try {
            Claims claims = parser.parseSignedClaims(authToken).getPayload();
            if (claims.getExpiration() == null || claims.getSubject() == null || claims.getSubject().isBlank()) {
                throw new InvalidSocialTokenException();
            }
            String tokenNonce = claims.get("nonce", String.class);
            if ((tokenNonce != null || nonce != null)
                    && (nonce == null || nonce.isBlank() || !nonce.equals(tokenNonce))) {
                throw new InvalidSocialTokenException();
            }
            return new SocialUserInfo(claims.getSubject(), claims.get("email", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidSocialTokenException();
        }
    }
}
