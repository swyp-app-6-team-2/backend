package com.star_pick.starpick.domain.auth.service;

import com.star_pick.starpick.domain.auth.client.SocialUserInfo;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClient;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClientResolver;
import com.star_pick.starpick.domain.auth.dto.SocialLoginRequest;
import com.star_pick.starpick.domain.auth.dto.SocialLoginResponse;
import com.star_pick.starpick.domain.user.entity.Provider;
import com.star_pick.starpick.domain.user.entity.SocialCredential;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.SocialCredentialRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class SocialLoginService {
    private final SocialUserInfoClientResolver socialUserInfoClientResolver;
    private final SocialCredentialRepository socialCredentialRepository;
    private final JwtProvider jwtProvider;
    private final TransactionTemplate transactionTemplate;
    private final RefreshTokenService refreshTokenService;

    public SocialLoginResponse login(SocialLoginRequest request) {
        Provider provider = parseProvider(request.provider());

        SocialUserInfoClient client = socialUserInfoClientResolver.resolve(provider);
        SocialUserInfo userInfo = client.getUserInfo(request.authToken(), request.nonce());

        // 외부 인증 호출은 끝난 뒤에 DB 트랜잭션을 시작한다.
        return transactionTemplate.execute(status -> socialCredentialRepository
                .findByProviderAndSocialUid(provider, userInfo.socialUid())
                .map(credential -> loginExistingUser(credential, provider))
                .orElseGet(() -> issueSignupToken(provider, userInfo)));
    }

    private SocialLoginResponse loginExistingUser(SocialCredential credential, Provider provider) {
        User user = credential.getUser();
        user.updateLastLogin(provider, LocalDateTime.now());

        JwtProvider.TokenPair tokens = refreshTokenService.issueAndStore(user.getUserId());

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
}
