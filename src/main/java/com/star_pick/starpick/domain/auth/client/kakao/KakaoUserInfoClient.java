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

@Component
public class KakaoUserInfoClient implements SocialUserInfoClient {

    private static final String KAKAO_USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient;

    public KakaoUserInfoClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public Provider getProvider() {
        return Provider.KAKAO;
    }

    @Override
    public SocialUserInfo getUserInfo(String authToken) {
        try {
            KakaoUserInfoResponse response = restClient.get()
                    .uri(KAKAO_USER_INFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);
            if (response == null || response.kakaoAccount() == null) { // || response.kakaoAccount().email() == null
                throw new InvalidSocialTokenException();
            }
            return new SocialUserInfo(String.valueOf(response.id()), response.kakaoAccount().email());
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
