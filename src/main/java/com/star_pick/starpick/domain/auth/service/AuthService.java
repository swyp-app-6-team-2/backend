package com.star_pick.starpick.domain.auth.service;

import com.star_pick.starpick.domain.user.service.UserLifecycleGuard;
import com.star_pick.starpick.domain.auth.client.SocialUserInfo;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClient;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClientResolver;
import com.star_pick.starpick.domain.auth.dto.SignupRequest;
import com.star_pick.starpick.domain.auth.dto.SignupResponse;
import com.star_pick.starpick.domain.auth.dto.SocialLoginRequest;
import com.star_pick.starpick.domain.auth.dto.SocialLoginResponse;
import com.star_pick.starpick.domain.auth.entity.RefreshToken;
import com.star_pick.starpick.domain.auth.exception.AuthErrorCode;
import com.star_pick.starpick.domain.auth.exception.SignupException;
import com.star_pick.starpick.domain.auth.repository.RefreshTokenRepository;
import com.star_pick.starpick.domain.user.entity.Provider;
import com.star_pick.starpick.domain.user.entity.SocialCredential;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.SocialCredentialRepository;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import com.star_pick.starpick.global.exception.ErrorCode;
import com.star_pick.starpick.global.security.jwt.JwtProvider.SignupIdentity;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final com.star_pick.starpick.domain.user.repository.ProfileRepository profiles;
    private final JwtProvider jwtProvider;
    private final UserLifecycleGuard lifecycle;
    private final UserRepository users;
    private final SocialCredentialRepository credentials;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SocialUserInfoClientResolver socialUserInfoClientResolver;
    private final TransactionTemplate transactions;

    public SignupResponse signup(SignupRequest request) {
        if (!Boolean.TRUE.equals(request.ageOver14Agreed())
                || !Boolean.TRUE.equals(request.serviceTermsAgreed()) || !Boolean.TRUE.equals(request.privacyAgreed())) {
            throw SignupException.termsRequired();
        }
        SignupIdentity identity;
        try {
            identity = jwtProvider.parseSignupToken(request.signupToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw SignupException.invalidToken();
        }
        try {
            return transactions.execute(status -> createUser(identity, request));
        } catch (DataIntegrityViolationException e) {
            // 동일 소셜 계정의 동시 가입은 DB UNIQUE가 최종 방어한다. 롤백 후에 번역한다.
            if (e.getCause() instanceof ConstraintViolationException violation
                    && "uk_provider_social_uid".equalsIgnoreCase(violation.getConstraintName())) {
                throw SignupException.alreadyRegistered();
            }
            throw e;
        }
    }

    private SignupResponse createUser(SignupIdentity identity, SignupRequest request) {
        if (credentials.findByProviderAndSocialUid(identity.provider(), identity.socialUid()).isPresent()) {
            throw SignupException.alreadyRegistered();
        }
        Instant now = Instant.now();
        User user = users.save(User.builder()
                .ageOver14Agreed(true)
                .ageOver14AgreedAt(now)
                .serviceTermsAgreed(true)
                .privacyAgreed(true)
                .marketingAgreed(request.marketingAgreed())
                .marketingAgreedAt(request.marketingAgreed() ? now : null)
                .serviceAgreed(request.serviceAgreed())
                .serviceAgreedAt(request.serviceAgreed() ? now : null)
                .signupCompletedAt(now)
                .lastLoginProvider(identity.provider())
                .lastLoginAt(now)
                .build());
        profiles.save(com.star_pick.starpick.domain.user.entity.Profile.initial(user));
        credentials.saveAndFlush(SocialCredential.builder()
                .user(user).provider(identity.provider()).socialUid(identity.socialUid()).email(identity.email()).build());
        JwtProvider.TokenPair tokens = issueForActiveUser(user.getUserId());
        return new SignupResponse(user.getUserId(), tokens.accessToken(), tokens.refreshToken(), user.isOnboardingRequired());
    }

    public SocialLoginResponse login(SocialLoginRequest request) {
        Provider provider = parseProvider(request.provider());

        SocialUserInfoClient client = socialUserInfoClientResolver.resolve(provider);
        SocialUserInfo userInfo = client.getUserInfo(request.authToken(), request.nonce());

        // 외부 인증 호출은 끝난 뒤에 DB 트랜잭션을 시작한다.
        return transactions.execute(status -> credentials
                .findByProviderAndSocialUid(provider, userInfo.socialUid())
                .map(credential -> loginExistingUser(credential, provider))
                .orElseGet(() -> issueSignupToken(provider, userInfo)));
    }

    private SocialLoginResponse loginExistingUser(SocialCredential credential, Provider provider) {
        User user = credential.getUser();
        user.updateLastLogin(provider, Instant.now());

        JwtProvider.TokenPair tokens = issueForActiveUser(user.getUserId());

        return SocialLoginResponse.ofExistingUser(user.getUserId(), tokens.accessToken(), tokens.refreshToken(), user.isOnboardingRequired());
    }

    private SocialLoginResponse issueSignupToken(Provider provider, SocialUserInfo userInfo) {
        String signupToken = jwtProvider.generateSignupToken(provider, userInfo.socialUid(), userInfo.email());

        return SocialLoginResponse.ofNewUser(signupToken);
    }

    private Provider parseProvider(String provider) {
        try {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException();
            }
            return Provider.valueOf(provider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }
    }

    @Transactional
    public JwtProvider.TokenPair issueAndStore(Long userId) {
        return issueForActiveUser(userId);
    }

    // 가입/로그인은 TransactionTemplate 안에서 호출하고, 독립 발급은 위 @Transactional을 거친다.
    private JwtProvider.TokenPair issueForActiveUser(Long userId) {
        lockActiveUser(userId, CommonErrorCode.AUTHENTICATION_REQUIRED);
        return issueForLockedUser(userId, refreshTokenRepository.findByUserId(userId).orElse(null));
    }

    @Transactional
    public void revokeForWithdrawal(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    @Transactional
    public void deleteCredentialsForWithdrawal(Long userId) {
        credentials.deleteByUser_UserId(userId);
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
        if (!identity.expiresAt().isAfter(Instant.now()) || !stored.getExpiresAt().isAfter(Instant.now())
                || !MessageDigest.isEqual(stored.getTokenHash().getBytes(StandardCharsets.UTF_8),
                        hash(refreshToken).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        return issueForLockedUser(identity.userId(), stored);
    }

    private JwtProvider.TokenPair issueForLockedUser(Long userId, RefreshToken existing) {
        JwtProvider.TokenPair tokens = jwtProvider.generateTokens(userId);
        // 별도로 now + TTL을 계산하지 않고 실제 JWT의 초 단위 exp와 맞춘다.
        Instant expiresAt = jwtProvider.parseRefreshToken(tokens.refreshToken()).expiresAt();
        String tokenHash = hash(tokens.refreshToken());
        if (existing == null) {
            refreshTokenRepository.save(RefreshToken.issue(userId, tokenHash, expiresAt));
        } else {
            existing.rotate(tokenHash, expiresAt);
        }
        return tokens;
    }

    private void lockActiveUser(Long userId, ErrorCode failure) {
        if (!lifecycle.lockIfActive(userId)) {
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
