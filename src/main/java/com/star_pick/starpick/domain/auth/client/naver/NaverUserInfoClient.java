package com.star_pick.starpick.domain.auth.client.naver;

import com.star_pick.starpick.domain.auth.client.SocialUserInfo;
import com.star_pick.starpick.domain.auth.client.SocialUserInfoClient;
import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import com.star_pick.starpick.domain.user.entity.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class NaverUserInfoClient implements SocialUserInfoClient {
    private static final String NAVER_USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    private final RestClient restClient;

    public NaverUserInfoClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public Provider getProvider() {
        return Provider.NAVER;
    }

    public SocialUserInfo getUserInfo(String authToken) {
        try {
            NaverUserInfoResponse result = restClient.get()
                    .uri(NAVER_USER_INFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                    .retrieve()
                    .body(NaverUserInfoResponse.class);

            if(result == null || !"00".equals(result.resultcode()) || result.response() == null) {
                throw new InvalidSocialTokenException();
            }

            return new SocialUserInfo(result.response().id(), result.response().email());

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new InvalidSocialTokenException();
            }
            log.error("네이버 사용자 정보 조회 실패 - status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new SocialAuthServerException(Provider.NAVER);
        } catch (RestClientException e) {
            log.error("네이버 사용자 정보 조회 중 오류", e);
            throw new SocialAuthServerException(Provider.NAVER);
        }
    }
}
