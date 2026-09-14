package com.star_pick.starpick.domain.auth.service;

import com.star_pick.starpick.domain.auth.dto.SignupRequest;
import com.star_pick.starpick.domain.auth.dto.SignupResponse;
import com.star_pick.starpick.domain.auth.exception.SignupException;
import com.star_pick.starpick.domain.user.entity.SocialCredential;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.SocialCredentialRepository;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.global.security.jwt.JwtProvider.SignupIdentity;
import io.jsonwebtoken.JwtException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class SignupService {
    private final JwtProvider jwtProvider;
    private final UserRepository users;
    private final SocialCredentialRepository credentials;
    private final TransactionTemplate transactions;
    private final RefreshTokenService refreshTokenService;

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
        LocalDateTime now = LocalDateTime.now();
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
        credentials.saveAndFlush(SocialCredential.builder()
                .user(user).provider(identity.provider()).socialUid(identity.socialUid()).email(identity.email()).build());
        JwtProvider.TokenPair tokens = refreshTokenService.issueAndStore(user.getUserId());
        return new SignupResponse(user.getUserId(), tokens.accessToken(), tokens.refreshToken(), user.isOnboardingRequired());
    }
}
