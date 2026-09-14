package com.star_pick.starpick.domain.auth.client.kakao;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoUserInfoClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KakaoUserInfoClient client = new KakaoUserInfoClient(builder, 1234);

    private void tokenInfo(long appId) {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
                .andExpect(header("Authorization", "Bearer kakao-token"))
                .andRespond(withSuccess("{\"id\":42,\"app_id\":" + appId + ",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    }

    @Test void acceptsMissingAccountAndEmail() {
        tokenInfo(1234);
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        var result = client.getUserInfo("kakao-token");
        assertThat(result.socialUid()).isEqualTo("42");
        assertThat(result.email()).isNull();
        server.verify();
    }

    @Test void rejectsOtherAppBeforeUserLookup() {
        tokenInfo(9999);
        assertThatThrownBy(() -> client.getUserInfo("kakao-token")).isInstanceOf(InvalidSocialTokenException.class);
        server.verify();
    }

    @Test void rejectsMismatchedUserId() {
        tokenInfo(1234);
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(withSuccess("{\"id\":99}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getUserInfo("kakao-token")).isInstanceOf(InvalidSocialTokenException.class);
        server.verify();
    }

    @Test void rejectsExpiredToken() {
        server.expect(anything()).andRespond(withUnauthorizedRequest());
        assertThatThrownBy(() -> client.getUserInfo("kakao-token")).isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test void distinguishesUpstreamFailure() {
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> client.getUserInfo("kakao-token")).isInstanceOf(SocialAuthServerException.class);
    }
}
