package com.star_pick.starpick.domain.auth.client.naver;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.star_pick.starpick.domain.auth.exception.AuthErrorCode;
import com.star_pick.starpick.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverTokenRevocationClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final NaverTokenRevocationClient client =
            new NaverTokenRevocationClient(builder, "naver-client", "naver-secret");

    @Test
    void revokesNaverConnectionWithFormEncodedToken() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andExpect(content().string(
                        "client_id=naver-client&client_secret=naver-secret&token=access-token&token_type_hint=access_token"))
                .andRespond(withSuccess());

        client.revoke("access-token");

        server.verify();
    }

    @Test
    void externalFailureIsTranslatedWithoutExposingTheToken() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.revoke("secret-access-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(AuthErrorCode.NAVER_CONNECTION_REVOKE_FAILED))
                .hasMessageNotContaining("secret-access-token");

        server.verify();
    }

    @Test
    void rejectsMissingConfiguration() {
        assertThatThrownBy(() -> new NaverTokenRevocationClient(RestClient.builder(), " ", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NaverTokenRevocationClient(RestClient.builder(), "client", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
