package com.star_pick.starpick.domain.auth.service;

import com.star_pick.starpick.domain.auth.client.SocialUserInfo;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClient;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClientResolver;
import com.star_pick.starpick.domain.auth.dto.SocialLoginRequest;
import com.star_pick.starpick.domain.auth.dto.SocialLoginResponse;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.domain.user.entity.Provider;
import com.star_pick.starpick.domain.user.entity.SocialCredential;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.SocialCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SocialLoginService {
    private final SocialUserInfoClientResolver socialUserInfoClientResolver;
    private final SocialCredentialRepository socialCredentialRepository;
    private final JwtProvider jwtProvider;

    public SocialLoginResponse login(SocialLoginRequest request) {
        Provider provider = parseProvider(request.provider());

        SocialUserInfoClient client = socialUserInfoClientResolver.resolve(provider);
        SocialUserInfo userInfo = client.getUserInfo(request.authToken());

        return socialCredentialRepository.findByProviderAndSocialUid(provider, userInfo.socialUid())
                .map(credential -> loginExistingUser(credential, provider))
                .orElseGet(() -> issueSignupToken(provider, userInfo));
    }

    private SocialLoginResponse loginExistingUser(SocialCredential credential, Provider provider) {
        User user = credential.getUser();
        user.updateLastLogin(provider, LocalDateTime.now());

        JwtProvider.TokenPair tokens = jwtProvider.generateTokens(user.getUserId());

        return SocialLoginResponse.ofExistingUser(user.getUserId(), tokens.accessToken(), tokens.refreshToken());
    }

    private SocialLoginResponse issueSignupToken(Provider provider, SocialUserInfo userInfo) {
        String signupToken = jwtProvider.generateSignupToken(provider, userInfo.socialUid(), userInfo.email());

        return SocialLoginResponse.ofNewUser(signupToken);
    }

    private Provider parseProvider(String provider) {
        try {
            return Provider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidSocialTokenException();
        }
    }
}
