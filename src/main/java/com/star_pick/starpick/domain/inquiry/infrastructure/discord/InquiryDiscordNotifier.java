package com.star_pick.starpick.domain.inquiry.infrastructure.discord;

import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 새 문의를 운영 Discord 채널에 알린다.
 *
 * <p><b>사용자가 쓴 글은 보내지 않는다.</b> 번호·유형·접수 시각·관리자 링크만 보낸다. 그래서 이 메서드는
 * 제목·내용·사진 Key 를 아예 파라미터로 받지 않는다.
 *
 * <p>재시도하지 않는다. 실패하면 그 알림은 누락되며, 문의 자체는 DB 에 있으므로 관리자 페이지에서 보인다.
 */
@Slf4j
@Component
public class InquiryDiscordNotifier {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final DateTimeFormatter RECEIVED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final RestClient restClient;
    private final String webhookUrl;

    @Autowired
    public InquiryDiscordNotifier(RestClient.Builder restClientBuilder,
                                  @Value("${inquiry.discord-webhook-url:}") String webhookUrl) {
        this(restClientBuilder.clone().requestFactory(requestFactory()).build(), webhookUrl);
    }

    /** 테스트가 {@code MockRestServiceServer} 를 끼우는 자리다. */
    InquiryDiscordNotifier(RestClient restClient, String webhookUrl) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** 트랜잭션 밖에서 한 번 부른다. 어떤 예외도 던지지 않는다. */
    public void notifyCreated(Long inquiryId, InquiryType type, Instant createdAt) {
        if (webhookUrl.isBlank()) {
            return;
        }
        try {
            // URL 을 <> 로 감싸 Discord 가 미리보기 카드를 붙이지 않게 한다(관리자 로그인 페이지가 펼쳐진다).
            String message = "문의 #%d · %s · %s · <%s>".formatted(
                    inquiryId, type, RECEIVED_AT.format(createdAt), adminUrl(inquiryId));
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("문의 접수 알림이 거절되었습니다. inquiryId={}, status={}", inquiryId, e.getStatusCode().value());
        } catch (Exception e) {
            // 예외 메시지에 웹훅 URL 이 들어갈 수 있어 클래스 이름만 남긴다.
            log.warn("문의 접수 알림 발송에 실패했습니다. inquiryId={}, error={}", inquiryId, e.getClass().getSimpleName());
        }
    }

    private static String adminUrl(Long inquiryId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/admin/inquiries/{id}")
                .buildAndExpand(inquiryId)
                .toUriString();
    }
}
