package com.star_pick.starpick.domain.notification.infrastructure.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.star_pick.starpick.domain.notification.service.PushMessage;
import com.star_pick.starpick.domain.notification.service.PushOutcome;
import com.star_pick.starpick.domain.notification.service.PushSendResult;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FcmPushGatewayTest {

    private final FirebaseMessaging messaging = mock(FirebaseMessaging.class);
    private final FcmPushGateway gateway = new FcmPushGateway(messaging);

    @Test
    @DisplayName("UNREGISTERED 만 토큰 무효로, SENDER_ID_MISMATCH·INVALID_ARGUMENT 는 실패로 분류한다")
    void mapsOutcomes() throws Exception {
        // 응답 mock 을 먼저 만든다. thenReturn 인자 안에서 다른 mock 을 stub 하면 UnfinishedStubbingException 이 난다.
        List<SendResponse> responses = List.of(
                success(), failure(MessagingErrorCode.UNREGISTERED),
                failure(MessagingErrorCode.SENDER_ID_MISMATCH), failure(MessagingErrorCode.INVALID_ARGUMENT));
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(responses);
        when(messaging.sendEach(anyList())).thenReturn(batch);

        List<PushSendResult> results = gateway.send(messages(4));

        assertThat(results).extracting(PushSendResult::outcome).containsExactly(
                PushOutcome.SENT, PushOutcome.TOKEN_UNREGISTERED, PushOutcome.FAILED, PushOutcome.FAILED);
        assertThat(results).extracting(PushSendResult::errorCode).containsExactly(
                null, "UNREGISTERED", "SENDER_ID_MISMATCH", "INVALID_ARGUMENT");
        assertThat(results).extracting(PushSendResult::pushLogId).containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    @DisplayName("500건씩 나눠 보내고, 한 묶음의 호출 예외는 그 묶음만 실패로 만든다")
    void batchesAndIsolatesFailure() throws Exception {
        List<SendResponse> responses = List.of(success());
        BatchResponse one = mock(BatchResponse.class);
        when(one.getResponses()).thenReturn(responses);
        when(messaging.sendEach(anyList()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(one);

        List<PushSendResult> results = gateway.send(messages(501));

        verify(messaging, times(2)).sendEach(anyList());
        assertThat(results).hasSize(501);
        assertThat(results.subList(0, 500)).allMatch(result -> result.outcome() == PushOutcome.FAILED);
        assertThat(results.get(500).outcome()).isEqualTo(PushOutcome.SENT);
    }

    @Test
    @DisplayName("OS 표시 알림 + 문자열 data + TTL 10분 + 높은 우선순위로 만든다")
    void buildsMessage() throws Exception {
        Instant now = Instant.parse("2026-09-14T03:00:00Z");
        Message message = FcmPushGateway.toMessage(
                new PushMessage(7L, 3L, "device-token", "Lunch", "body-text", "starpick://recommend"), now);

        String json = GsonFactory.getDefaultInstance().toString(message);

        assertThat(json).contains(
                "\"token\":\"device-token\"",
                "\"title\":\"Lunch\"", "\"body\":\"body-text\"",
                "\"notificationId\":\"7\"", "\"deepLink\":\"starpick://recommend\"",
                "\"priority\":\"high\"", "\"ttl\":\"600s\"",
                "\"apns-priority\":\"10\"",
                "\"apns-expiration\":\"" + now.plusSeconds(600).getEpochSecond() + "\"",
                "\"sound\":\"default\"");
    }

    private List<PushMessage> messages(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new PushMessage(index, 100 + index, "token-" + index, "title", "body", "link"))
                .toList();
    }

    private SendResponse success() {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(true);
        return response;
    }

    private SendResponse failure(MessagingErrorCode code) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(code);
        when(exception.getErrorCode()).thenReturn(ErrorCode.UNKNOWN);
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getException()).thenReturn(exception);
        return response;
    }
}
