package com.star_pick.starpick.domain.auth.service;

import com.star_pick.starpick.domain.auth.entity.RefreshToken;
import com.star_pick.starpick.domain.auth.repository.RefreshTokenRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import com.star_pick.starpick.global.exception.ErrorCode;
import com.star_pick.starpick.domain.auth.exception.AuthErrorCode;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Transactional
    public JwtProvider.TokenPair issueAndStore(Long userId) {
        lockActiveUser(userId, CommonErrorCode.AUTHENTICATION_REQUIRED);
        return issueForLockedUser(userId, refreshTokenRepository.findByUserId(userId).orElse(null));
    }

    @Transactional
    public void revoke(Long userId) {
        lockActiveUser(userId, CommonErrorCode.AUTHENTICATION_REQUIRED);
        refreshTokenRepository.deleteByUserId(userId);
    }

    /** 해시 확인과 회전을 같은 사용자 잠금 안에서 처리해 한 토큰의 동시 소비를 막는다. */
    @Transactional
    public JwtProvider.TokenPair refresh(String refreshToken) {
        JwtProvider.RefreshIdentity identity;
        try {
            identity = jwtProvider.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        lockActiveUser(identity.userId(), AuthErrorCode.REFRESH_TOKEN_INVALID);
        RefreshToken stored = refreshTokenRepository.findByUserId(identity.userId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        // 잠금 대기 중 만료되었을 수도 있어 JWT와 DB 만료를 여기서 다시 확인한다.
        if (!identity.expiresAt().isAfter(Instant.now()) || !stored.getExpiresAt().isAfter(LocalDateTime.now())
                || !MessageDigest.isEqual(stored.getTokenHash().getBytes(StandardCharsets.UTF_8),
                        hash(refreshToken).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        return issueForLockedUser(identity.userId(), stored);
    }

    private JwtProvider.TokenPair issueForLockedUser(Long userId, RefreshToken existing) {
        JwtProvider.TokenPair tokens = jwtProvider.generateTokens(userId);
        // 별도로 now + TTL을 계산하지 않고 실제 JWT의 초 단위 exp와 맞춘다.
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                jwtProvider.parseRefreshToken(tokens.refreshToken()).expiresAt(), ZoneId.systemDefault());
        String tokenHash = hash(tokens.refreshToken());
        if (existing == null) {
            refreshTokenRepository.save(RefreshToken.issue(userId, tokenHash, expiresAt));
        } else {
            existing.rotate(tokenHash, expiresAt);
        }
        return tokens;
    }

    private void lockActiveUser(Long userId, ErrorCode failure) {
        var user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(failure));
        if (user.getDeletedAt() != null) {
            throw new BusinessException(failure);
        }
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 찾을 수 없습니다.", e);
        }
    }
}
