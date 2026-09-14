package com.star_pick.starpick.global.security.jwt;

import com.star_pick.starpick.domain.user.entity.Provider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;


import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.time.Instant;

/** 서비스 로그인 토큰 및 소셜 인증 후 임시 가입 토큰 발급·검증. */

@Component
public class JwtProvider {
    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final long signupTokenExpiration;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration,
            @Value("${jwt.signup-token-expiration}") long signupTokenExpiration
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // JwtParser 는 immutable·thread-safe 다. 요청마다 다시 만들지 않는다.
        this.jwtParser = Jwts.parser().verifyWith(this.secretKey).build();
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.signupTokenExpiration = signupTokenExpiration;
    }

    public TokenPair generateTokens(Long userId) {
        String accessToken = generateToken(userId.toString(), "access", accessTokenExpiration);
        String refreshToken = generateToken(userId.toString(), "refresh", refreshTokenExpiration);
        return new TokenPair(accessToken, refreshToken);
    }

    public String generateSignupToken(Provider provider,String socialUid, String email) {
        return Jwts.builder()
                .claim("type", "signup")
                .claim("provider", provider.name())
                .claim("socialUid", socialUid)
                .claim("email", email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + signupTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    private String generateToken(String subject, String type, long expiration) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .claim("type", type)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    /** 서비스에서 발급한 refresh token만 허용하며, exp는 필수다. */
    public RefreshIdentity parseRefreshToken(String token) {
        Claims claims = jwtParser.parseSignedClaims(token).getPayload();
        if (!"refresh".equals(claims.get("type", String.class)) || claims.getExpiration() == null
                || claims.getId() == null || claims.getId().isBlank()) {
            throw new JwtException("유효한 refresh token이 아닙니다.");
        }
        long userId = Long.parseLong(claims.getSubject());
        if (userId <= 0) {
            throw new JwtException("유효한 사용자 식별자가 아닙니다.");
        }
        return new RefreshIdentity(userId, claims.getExpiration().toInstant());
    }

    public record RefreshIdentity(Long userId, Instant expiresAt) { }

    /**
     * access token 을 검증하고 userId 를 반환한다.
     *
     * @throws JwtException 서명 불일치, 만료, access token 이 아닌 경우
     */
    public Long parseAccessToken(String token) {
        Claims claims = jwtParser.parseSignedClaims(token).getPayload();

        if (!"access".equals(claims.get("type", String.class))) {
            throw new JwtException("access token 이 아니다");
        }
        return Long.valueOf(claims.getSubject());
    }

    public SignupIdentity parseSignupToken(String token) {
        Claims claims = jwtParser.parseSignedClaims(token).getPayload();
        if (!"signup".equals(claims.get("type", String.class)) || claims.getExpiration() == null) {
            throw new JwtException("유효한 signup token이 아닙니다.");
        }
        String provider = claims.get("provider", String.class);
        String socialUid = claims.get("socialUid", String.class);
        String email = claims.get("email", String.class);
        if (provider == null || socialUid == null || socialUid.isBlank() || socialUid.length() > 255
                || (email != null && email.length() > 255)) {
            throw new JwtException("회원가입 식별 정보가 올바르지 않습니다.");
        }
        try {
            return new SignupIdentity(Provider.valueOf(provider), socialUid, email);
        } catch (IllegalArgumentException e) {
            throw new JwtException("지원하지 않는 provider입니다.");
        }
    }

    public record SignupIdentity(Provider provider, String socialUid, String email) { }

    public record TokenPair(String accessToken, String refreshToken) { }


}
