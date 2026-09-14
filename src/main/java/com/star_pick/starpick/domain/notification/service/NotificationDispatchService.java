package com.star_pick.starpick.domain.notification.service;

import com.star_pick.starpick.domain.notification.config.NotificationProperties;
import com.star_pick.starpick.domain.notification.domain.PushStatus;
import com.star_pick.starpick.domain.notification.domain.TimeSlot;
import com.star_pick.starpick.domain.notification.repository.DueNotification;
import com.star_pick.starpick.domain.notification.repository.DueNotificationRecorder;
import com.star_pick.starpick.domain.notification.repository.PushLogRepository;
import com.star_pick.starpick.domain.notification.repository.PushTokenRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 매분 한 번: 기록 → 발송 → 결과 반영. 한 번만 시도하고 재시도하지 않는다.
 *
 * <p>이 메서드에는 트랜잭션을 걸지 않는다. DB 호출은 각자 트랜잭션을 가진 Repository 메서드로 하고,
 * FCM 호출은 그 사이에서 트랜잭션 밖으로 일어난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final DueNotificationRecorder recorder;
    private final PushGateway pushGateway;
    private final PushLogRepository pushLogRepository;
    private final PushTokenRepository pushTokenRepository;
    private final NotificationProperties properties;

    public void dispatch(Instant now) {
        // 실행 시각이 속한 분을 그대로 쓴다. 스케줄은 매분 1초에 돌아 몇 ms 일찍 깨도, 59초까지 늦게 시작해도 같은 분이다.
        ZonedDateTime minute = now.atZone(SEOUL).truncatedTo(ChronoUnit.MINUTES);
        List<DueNotification> due = recorder.record(
                minute.getDayOfWeek().name(), minute.format(TimeSlot.HH_MM), minute.toInstant());
        if (due.isEmpty()) {
            return;
        }

        List<PushSendResult> results = pushGateway.send(due.stream()
                .map(notification -> new PushMessage(notification.pushLogId(), notification.pushTokenId(),
                        notification.token(), notification.label(),
                        properties.message().mealBody(), properties.push().deepLink()))
                .toList());
        apply(results);
    }

    private void apply(List<PushSendResult> results) {
        List<Long> sent = idsOf(results, PushOutcome.SENT);
        List<Long> failed = results.stream()
                .filter(result -> result.outcome() != PushOutcome.SENT).map(PushSendResult::pushLogId).toList();
        List<Long> unregisteredTokens = results.stream()
                .filter(result -> result.outcome() == PushOutcome.TOKEN_UNREGISTERED)
                .map(PushSendResult::pushTokenId).toList();

        if (!sent.isEmpty()) {
            pushLogRepository.updateStatus(sent, PushStatus.SENT);
        }
        if (!failed.isEmpty()) {
            pushLogRepository.updateStatus(failed, PushStatus.FAILED);
        }
        if (!unregisteredTokens.isEmpty()) {
            pushTokenRepository.deactivateAll(unregisteredTokens);
        }

        log.info("식사 알림을 보냈습니다. total={}, sent={}, failed={}, unregisteredTokens={}",
                results.size(), sent.size(), failed.size(), unregisteredTokens.size());
        Map<String, Long> failureCodes = results.stream()
                .filter(result -> result.outcome() == PushOutcome.FAILED)
                .collect(Collectors.groupingBy(result -> String.valueOf(result.errorCode()), Collectors.counting()));
        if (!failureCodes.isEmpty()) {
            log.warn("식사 알림 일부가 실패했습니다. errorCodes={}", failureCodes);
        }
    }

    private List<Long> idsOf(List<PushSendResult> results, PushOutcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).map(PushSendResult::pushLogId).toList();
    }
}
