package com.star_pick.starpick.global.security.jwt;

import com.star_pick.starpick.domain.user.entity.Provider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;


import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/*
  < 토큰 발급 유틸 >
   - 일단 발급만 구현 후, 검증/해석 로직은 회원가입API와 Security필터 만들 때 추가 예정
 */

@Component
public class JwtProvider {
    private final SecretKey secretKey;
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

    public record TokenPair(String accessToken, String refreshToken) { }


}
