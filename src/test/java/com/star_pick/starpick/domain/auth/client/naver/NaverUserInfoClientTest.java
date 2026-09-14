package com.star_pick.starpick.domain.auth.client.naver;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverUserInfoClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final NaverUserInfoClient client = new NaverUserInfoClient(builder);

    @Test void acceptsMissingEmailAndExtraFields() {
        server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
                .andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{\"id\":\"naver-id\",\"nickname\":\"test\"}}", MediaType.APPLICATION_JSON));
        var result = client.getUserInfo("token");
        assertThat(result.socialUid()).isEqualTo("naver-id");
        assertThat(result.email()).isNull();
    }

    @Test void rejectsMissingIdentity() {
        server.expect(anything()).andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getUserInfo("token")).isInstanceOf(InvalidSocialTokenException.class);
    }
}
