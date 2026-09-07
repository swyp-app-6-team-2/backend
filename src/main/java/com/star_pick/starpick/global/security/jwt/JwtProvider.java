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

/*
  < 토큰 발급·검증 유틸 >
   - signup token 의 검증은 회원가입 API 담당자가 추가한다
 */

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
                .subject(subject)
                .claim("type", type)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

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

    public record TokenPair(String accessToken, String refreshToken) { }


}
