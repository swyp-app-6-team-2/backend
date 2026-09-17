package com.star_pick.starpick.domain.inquiry.infrastructure.discord;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class InquiryDiscordNotifierTest {

    private static final String WEBHOOK_URL = "https://discord.test/api/webhooks/1/token";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    /** 관리자 링크는 요청 호스트에서 만든다. MockHttpServletRequest 기본값이 http://localhost 다. */
    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private InquiryDiscordNotifier notifier(String webhookUrl) {
        return new InquiryDiscordNotifier(builder.build(), webhookUrl);
    }

    @Test
    @DisplayName("번호·유형 표시 이름·제목·접수 시각·관리자 링크를 한 줄로 보낸다")
    void sendsCompactLine() {
        server.expect(requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"content":"문의 #12 · 별 슬롯 확장 · 광고 봤는데 슬롯이 안 늘어나요 · 2026-09-16 21:04 · <http://localhost/admin/inquiries/12>"}"""))
                .andRespond(withSuccess());

        notifier(WEBHOOK_URL).notifyCreated(12L, InquiryType.SLOT, "광고 봤는데 슬롯이 안 늘어나요",
                Instant.parse("2026-09-16T12:04:30Z"));

        server.verify();
    }

    @Test
    @DisplayName("제목의 줄바꿈은 공백으로 바꾸고, 멘션과 링크 미리보기는 막는다")
    void neutralizesUserTitle() {
        server.expect(requestTo(WEBHOOK_URL))
                .andExpect(content().json("""
                        {"content":"문의 #12 · 오류 신고 · @everyone 급해요 https://example.com · 2026-09-16 21:04 · <http://localhost/admin/inquiries/12>",
                         "allowed_mentions":{"parse":[]},
                         "flags":4}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess());

        notifier(WEBHOOK_URL).notifyCreated(12L, InquiryType.BUG, "@everyone\n급해요\r\nhttps://example.com",
                Instant.parse("2026-09-16T12:04:30Z"));

        server.verify();
    }

    @Test
    @DisplayName("발송이 실패해도 예외를 던지지 않는다")
    void swallowsFailure() {
        server.expect(requestTo(WEBHOOK_URL)).andRespond(withServerError());

        assertThatCode(() -> notifier(WEBHOOK_URL).notifyCreated(12L, InquiryType.BUG, "제목", Instant.now()))
                .doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("웹훅 주소가 없으면 호출하지 않는다")
    void doesNothingWithoutWebhookUrl() {
        notifier("").notifyCreated(12L, InquiryType.ETC, "제목", Instant.now());

        server.verify(); // 기대한 요청이 없으므로 한 건이라도 나가면 실패한다
    }

    @Test
    @DisplayName("요청 컨텍스트가 없어 링크를 만들 수 없으면 보내지 않고 예외도 없다")
    void swallowsMissingRequestContext() {
        RequestContextHolder.resetRequestAttributes();

        assertThatCode(() -> notifier(WEBHOOK_URL).notifyCreated(12L, InquiryType.ETC, "제목", Instant.now()))
                .doesNotThrowAnyException();

        server.verify();
    }
}
