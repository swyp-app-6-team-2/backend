package com.star_pick.starpick.domain.notification.infrastructure.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.star_pick.starpick.domain.notification.service.PushGateway;
import com.star_pick.starpick.domain.notification.service.PushMessage;
import com.star_pick.starpick.domain.notification.service.PushOutcome;
import com.star_pick.starpick.domain.notification.service.PushSendResult;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * FCM 호출 1회와 결과 분류만 한다. 재시도하지 않는다(SDK 기본 재시도는 503 에만 최대 4회이며 끌 수 없다).
 * 토큰 원문·응답 원문은 로그에 남기지 않는다.
 */
@Slf4j
public class FcmPushGateway implements PushGateway {

    /** 꺼져 있던 폰에 한참 지난 식사 알림이 도착하지 않게 한다. 계산값이 아니라 고정이라 음수가 될 일이 없다. */
    static final Duration PUSH_TTL = Duration.ofMinutes(10);
    static final int BATCH_SIZE = 500;
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final String APP_NAME = "starpick-notification";

    private final String projectId;
    private FirebaseApp app;
    private FirebaseMessaging messaging;

    public FcmPushGateway(String projectId) {
        this.projectId = projectId;
    }

    FcmPushGateway(FirebaseMessaging messaging) {
        this.projectId = null;
        this.messaging = messaging;
    }

    @Override
    public List<PushSendResult> send(List<PushMessage> messages) {
        List<PushSendResult> results = new ArrayList<>(messages.size());
        for (int from = 0; from < messages.size(); from += BATCH_SIZE) {
            results.addAll(sendBatch(messages.subList(from, Math.min(from + BATCH_SIZE, messages.size()))));
        }
        return results;
    }

    private List<PushSendResult> sendBatch(List<PushMessage> batch) {
        Instant now = Instant.now();
        try {
            List<SendResponse> responses = messaging()
                    .sendEach(batch.stream().map(message -> toMessage(message, now)).toList())
                    .getResponses();
            List<PushSendResult> results = new ArrayList<>(batch.size());
            for (int index = 0; index < batch.size(); index++) {
                results.add(toResult(batch.get(index), responses.get(index)));
            }
            return results;
        } catch (FirebaseMessagingException | RuntimeException e) {
            // 자격증명 누락(첫 초기화 실패)도 여기로 온다.
            log.error("FCM 묶음 발송에 실패했습니다. size={}", batch.size(), e);
            String code = e instanceof FirebaseMessagingException fme ? fme.getErrorCode().name() : e.getClass().getSimpleName();
            return batch.stream()
                    .map(message -> new PushSendResult(message.pushLogId(), message.pushTokenId(), PushOutcome.FAILED, code))
                    .toList();
        }
    }

    static Message toMessage(PushMessage message, Instant now) {
        return Message.builder()
                .setToken(message.token())
                .setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
                .putData("notificationId", String.valueOf(message.pushLogId()))
                .putData("deepLink", message.deepLink())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setTtl(PUSH_TTL.toMillis())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .putHeader("apns-expiration", String.valueOf(now.plus(PUSH_TTL).getEpochSecond()))
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .build();
    }

    /** UNREGISTERED 만 토큰을 끈다. SENDER_ID_MISMATCH 는 서버 설정 실수로도, INVALID_ARGUMENT 는 payload 버그로도 난다. */
    static PushSendResult toResult(PushMessage message, SendResponse response) {
        if (response.isSuccessful()) {
            return new PushSendResult(message.pushLogId(), message.pushTokenId(), PushOutcome.SENT, null);
        }
        FirebaseMessagingException exception = response.getException();
        MessagingErrorCode code = exception.getMessagingErrorCode();
        if (code == MessagingErrorCode.UNREGISTERED) {
            return new PushSendResult(message.pushLogId(), message.pushTokenId(), PushOutcome.TOKEN_UNREGISTERED, code.name());
        }
        String name = code != null ? code.name() : exception.getErrorCode().name();
        return new PushSendResult(message.pushLogId(), message.pushTokenId(), PushOutcome.FAILED, name);
    }

    /** 첫 발송 때 초기화한다. 기동 시 만들면 ADC 가 없는 팀원 로컬에서 앱이 뜨지 않는다. */
    private synchronized FirebaseMessaging messaging() {
        if (messaging == null) {
            try {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.getApplicationDefault())
                        .setProjectId(projectId)
                        .setConnectTimeout(TIMEOUT_MILLIS)
                        .setReadTimeout(TIMEOUT_MILLIS)
                        .setWriteTimeout(TIMEOUT_MILLIS)
                        .build();
                app = FirebaseApp.initializeApp(options, APP_NAME);
                messaging = FirebaseMessaging.getInstance(app);
            } catch (IOException e) {
                throw new UncheckedIOException("Google 자격증명(ADC)을 찾지 못했습니다.", e);
            }
        }
        return messaging;
    }

    @PreDestroy
    public synchronized void close() {
        if (app != null) {
            app.delete();
        }
    }
}
