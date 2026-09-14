package com.star_pick.starpick.domain.auth.client.apple;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import com.star_pick.starpick.domain.user.entity.Provider;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 신뢰하는 고정 Apple JWKS 주소만 사용한다. 토큰의 jku/x5u URL은 사용하지 않는다. */
@Component
public class AppleSigningKeyProvider {
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration REFRESH_COOLDOWN = Duration.ofMinutes(1);
    private final RestClient restClient;
    private final Clock clock;
    private Map<String, PublicKey> keys = Map.of();
    private Instant fetchedAt;
    private Instant attemptedAt;
    private boolean lastRefreshFailed;

    @Autowired
    public AppleSigningKeyProvider(RestClient.Builder builder) {
        this(withTimeouts(builder), Clock.systemUTC());
    }

    AppleSigningKeyProvider(RestClient.Builder builder, Clock clock) {
        this.restClient = builder.build();
        this.clock = clock;
    }

    private static RestClient.Builder withTimeouts(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return builder.clone().requestFactory(factory);
    }

    public synchronized PublicKey find(String kid) {
        if (kid == null || kid.isBlank()) {
            throw new InvalidSocialTokenException();
        }
        Instant now = clock.instant();
        boolean fresh = fetchedAt != null && now.isBefore(fetchedAt.plus(CACHE_TTL));
        if (fresh && keys.containsKey(kid)) {
            return keys.get(kid);
        }
        // 키 회전 시 새 kid는 즉시 갱신하되, 임의 kid 요청마다 Apple을 호출하지 않는다.
        if (attemptedAt == null || !now.isBefore(attemptedAt.plus(REFRESH_COOLDOWN))) {
            refresh(now);
        } else if (lastRefreshFailed) {
            throw new SocialAuthServerException(Provider.APPLE);
        }
        PublicKey key = keys.get(kid);
        if (key == null) {
            throw new InvalidSocialTokenException();
        }
        return key;
    }

    private void refresh(Instant now) {
        attemptedAt = now;
        lastRefreshFailed = true;
        try {
            KeySet response = restClient.get().uri("https://appleid.apple.com/auth/keys")
                    .retrieve().body(KeySet.class);
            if (response == null || response.keys() == null) {
                throw new SocialAuthServerException(Provider.APPLE);
            }
            Map<String, PublicKey> loaded = new HashMap<>();
            for (JsonKey key : response.keys()) {
                if (key == null || !"RSA".equals(key.kty()) || !"sig".equals(key.use())
                        || !"RS256".equals(key.alg())) {
                    continue;
                }
                if (key.kid() == null || key.kid().isBlank() || key.n() == null || key.e() == null) {
                    throw new SocialAuthServerException(Provider.APPLE);
                }
                RSAPublicKeySpec spec = new RSAPublicKeySpec(
                        new BigInteger(1, Base64.getUrlDecoder().decode(key.n())),
                        new BigInteger(1, Base64.getUrlDecoder().decode(key.e())));
                loaded.put(key.kid(), KeyFactory.getInstance("RSA").generatePublic(spec));
            }
            if (loaded.isEmpty()) {
                throw new SocialAuthServerException(Provider.APPLE);
            }
            keys = Map.copyOf(loaded);
            fetchedAt = now;
            lastRefreshFailed = false;
        } catch (RestClientException | GeneralSecurityException | IllegalArgumentException e) {
            throw new SocialAuthServerException(Provider.APPLE);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeySet(List<JsonKey> keys) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonKey(String kty, String kid, String use, String alg, String n, String e) { }
}
