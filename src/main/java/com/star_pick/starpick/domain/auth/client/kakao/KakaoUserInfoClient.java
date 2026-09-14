package com.star_pick.starpick.domain.auth.client.kakao;

import com.star_pick.starpick.domain.auth.client.SocialUserInfo;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClient;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import com.star_pick.starpick.domain.user.entity.Provider;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.beans.factory.annotation.Value;

@Component
public class KakaoUserInfoClient implements SocialUserInfoClient {

    private static final String KAKAO_USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient;
    private final long appId;

    public KakaoUserInfoClient(RestClient.Builder restClientBuilder, @Value("${kakao.app-id}") long appId) {
        if (appId <= 0) {
            throw new IllegalArgumentException("kakao.app-id는 양수여야 합니다.");
        }
        this.restClient = restClientBuilder.build();
        this.appId = appId;
    }

    @Override
    public Provider getProvider() {
        return Provider.KAKAO;
    }

    @Override
    public SocialUserInfo getUserInfo(String authToken) {
        try {
            KakaoTokenInfoResponse tokenInfo = restClient.get()
                    .uri("https://kapi.kakao.com/v1/user/access_token_info")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                    .retrieve().body(KakaoTokenInfoResponse.class);
            if (tokenInfo == null || tokenInfo.id() == null || tokenInfo.appId() == null
                    || tokenInfo.appId() != appId || tokenInfo.expiresIn() == null || tokenInfo.expiresIn() <= 0) {
                throw new InvalidSocialTokenException();
            }
            KakaoUserInfoResponse response = restClient.get()
                    .uri(KAKAO_USER_INFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);
            if (response == null || response.id() == null || !response.id().equals(tokenInfo.id())) {
                throw new InvalidSocialTokenException();
            }
            return new SocialUserInfo(String.valueOf(response.id()),
                    response.kakaoAccount() == null ? null : response.kakaoAccount().email());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new InvalidSocialTokenException();
            }
            throw new SocialAuthServerException(Provider.KAKAO);
        } catch (RestClientException e) {
            throw new SocialAuthServerException(Provider.KAKAO);
        }
    }
}
