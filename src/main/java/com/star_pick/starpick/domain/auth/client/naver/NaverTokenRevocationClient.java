package com.star_pick.starpick.domain.auth.client.naver;

import com.star_pick.starpick.domain.auth.exception.AuthErrorCode;
import com.star_pick.starpick.global.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class NaverTokenRevocationClient {
    private static final String NAVER_TOKEN_REVOKE_URI = "https://nid.naver.com/oauth2.0/revoke";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public NaverTokenRevocationClient(RestClient.Builder restClientBuilder,
            @Value("${naver.client-id}") String clientId,
            @Value("${naver.client-secret}") String clientSecret) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("naver.client-id는 필수입니다.");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("naver.client-secret은 필수입니다.");
        }
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void revoke(String accessToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("token", accessToken);
        form.add("token_type_hint", "access_token");

        try {
            restClient.post()
                    .uri(NAVER_TOKEN_REVOKE_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("네이버 계정 연결 해제 실패", e);
            throw new BusinessException(AuthErrorCode.NAVER_CONNECTION_REVOKE_FAILED);
        }
    }
}
