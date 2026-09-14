package com.star_pick.starpick.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.notification.controller.request.NotificationSettingRequest;
import com.star_pick.starpick.domain.notification.controller.request.TimeSlotRequest;
import com.star_pick.starpick.domain.notification.domain.PushPlatform;
import com.star_pick.starpick.support.FakePushGateway;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

// @Transactional 을 붙이지 않는다. 롤백이 기록 문장의 commit 경계와 트랜잭션 속성 문제를 가린다.
@IntegrationTest
class NotificationDispatchServiceTest {

    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;
    private static final Long USER_C = 3L;
    /** 2026-09-14 월요일 12:00 (Asia/Seoul) */
    private static final Instant MONDAY_NOON = Instant.parse("2026-09-14T03:00:00Z");

    @Autowired
    private NotificationDispatchService dispatchService;
    @Autowired
    private NotificationSettingService settingService;
    @Autowired
    private PushTokenService tokenService;
    @Autowired
    private FakePushGateway gateway;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        gateway.clear();
        fixtures.seedUser(USER_A);
        fixtures.seedUser(USER_B);
    }

    @Test
    @DisplayName("요일·시각이 맞는 켜진 설정의 활성 토큰마다 한 건씩 보낸다")
    void sendsToMatchingTokens() {
        save(USER_A, true, List.of(DayOfWeek.MONDAY), "점심 알림", "12:00");
        tokenService.register(USER_A, "phone", PushPlatform.IOS);
        tokenService.register(USER_A, "tablet", PushPlatform.ANDROID);
        save(USER_B, true, List.of(DayOfWeek.TUESDAY), "점심", "12:00");
        tokenService.register(USER_B, "other", PushPlatform.IOS);

        dispatchService.dispatch(MONDAY_NOON);

        assertThat(gateway.sent()).extracting(PushMessage::token).containsExactlyInAnyOrder("phone", "tablet");
        PushMessage message = gateway.sent().getFirst();
        assertThat(message.title()).isEqualTo("점심 알림");
        assertThat(message.body()).isEqualTo("test-meal-body");
        assertThat(message.deepLink()).isEqualTo("starpick-test://recommend");
        assertThat(statuses()).containsExactly("SENT", "SENT");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from push_log where user_id = ? and scheduled_at = cast(? as timestamptz)",
                Integer.class, USER_A, MONDAY_NOON.toString())).isEqualTo(2);
    }

    @Test
    @DisplayName("스케줄러가 몇 ms 일찍 깨어도 그 분의 알림을 보낸다")
    void earlyWakeRoundsToMinute() {
        save(USER_A, true, List.of(DayOfWeek.MONDAY), "점심", "12:00");
        tokenService.register(USER_A, "phone", PushPlatform.IOS);

        dispatchService.dispatch(MONDAY_NOON.minusMillis(5));

        assertThat(gateway.sent()).hasSize(1);
    }

    @Test
    @DisplayName("꺼진 설정, 비활성 토큰, 다른 시각은 보내지 않고 대상이 없으면 게이트웨이를 부르지 않는다")
    void excludesNonTargets() {
        save(USER_A, false, List.of(DayOfWeek.MONDAY), "점심", "12:00");
        tokenService.register(USER_A, "off-setting", PushPlatform.IOS);
        save(USER_B, true, List.of(DayOfWeek.MONDAY), "점심", "12:01");
        tokenService.register(USER_B, "other-time", PushPlatform.IOS);
        // 요일·시각이 맞는 켜진 설정이어야 비활성 토큰 조건만 따로 검증된다.
        fixtures.seedUser(USER_C);
        save(USER_C, true, List.of(DayOfWeek.MONDAY), "점심", "12:00");
        tokenService.register(USER_C, "logged-out", PushPlatform.IOS);
        tokenService.unregister(USER_C, "logged-out");

        dispatchService.dispatch(MONDAY_NOON);

        assertThat(gateway.calls()).isZero();
        assertThat(statuses()).isEmpty();
    }

    @Test
    @DisplayName("같은 분에 다시 실행해도 두 번 보내지 않는다")
    void rerunSameMinute() {
        save(USER_A, true, List.of(DayOfWeek.MONDAY), "점심", "12:00");
        tokenService.register(USER_A, "phone", PushPlatform.IOS);

        dispatchService.dispatch(MONDAY_NOON);
        dispatchService.dispatch(MONDAY_NOON.plusSeconds(10));

        assertThat(gateway.calls()).isOne();
        assertThat(statuses()).hasSize(1);
    }

    @Test
    @DisplayName("한 설정에 같은 시각이 두 번 들어가 있어도 토큰당 한 건만 보낸다")
    void duplicateSlotSendsOnce() {
        save(USER_A, true, List.of(DayOfWeek.MONDAY), "점심", "12:00");
        tokenService.register(USER_A, "phone", PushPlatform.IOS);
        jdbcTemplate.update("""
                update notification_setting
                   set time_slots = '[{"label":"a","time":"12:00"},{"label":"b","time":"12:00"}]'::jsonb
                 where user_id = ?
                """, USER_A);

        dispatchService.dispatch(MONDAY_NOON);

        assertThat(gateway.sent()).hasSize(1);
    }

    @Test
    @DisplayName("결과별로 상태를 반영하고 UNREGISTERED 토큰만 비활성화한다")
    void appliesOutcomes() {
        save(USER_A, true, List.of(DayOfWeek.MONDAY), "점심", "12:00");
        tokenService.register(USER_A, "dead", PushPlatform.IOS);
        tokenService.register(USER_A, "flaky", PushPlatform.IOS);
        gateway.respond(tokenId("dead"), PushOutcome.TOKEN_UNREGISTERED, "UNREGISTERED");
        gateway.respond(tokenId("flaky"), PushOutcome.FAILED, "UNAVAILABLE");

        dispatchService.dispatch(MONDAY_NOON);

        assertThat(statuses()).containsExactly("FAILED", "FAILED");
        assertThat(active("dead")).isFalse();
        assertThat(active("flaky")).isTrue();
    }

    @Test
    @DisplayName("결과 반영이 먼저 기록된 opened_at 을 덮지 않는다")
    void keepsOpenedAt() {
        save(USER_A, true, List.of(DayOfWeek.MONDAY), "점심", "12:00");
        tokenService.register(USER_A, "phone", PushPlatform.IOS);
        gateway.onSend(messages -> jdbcTemplate.update(
                "update push_log set opened_at = now() where id = ?", messages.getFirst().pushLogId()));

        dispatchService.dispatch(MONDAY_NOON);

        assertThat(jdbcTemplate.queryForObject(
                "select opened_at is not null from push_log", Boolean.class)).isTrue();
        assertThat(statuses()).containsExactly("SENT");
    }

    @Test
    @DisplayName("일요일 00:00 시간대는 토요일 15:00(UTC) 실행에서 보낸다")
    void sundayMidnight() {
        save(USER_A, true, List.of(DayOfWeek.SUNDAY), "야식", "00:00");
        tokenService.register(USER_A, "phone", PushPlatform.IOS);

        dispatchService.dispatch(Instant.parse("2026-09-12T15:00:00Z"));

        assertThat(gateway.sent()).hasSize(1);
    }

    private void save(Long userId, boolean enabled, List<DayOfWeek> weekdays, String label, String time) {
        settingService.save(userId, new NotificationSettingRequest(
                enabled, weekdays, List.of(new TimeSlotRequest(label, LocalTime.parse(time)))));
    }

    private long tokenId(String token) {
        return jdbcTemplate.queryForObject("select id from push_token where token = ?", Long.class, token);
    }

    private boolean active(String token) {
        return jdbcTemplate.queryForObject("select active from push_token where token = ?", Boolean.class, token);
    }

    private List<String> statuses() {
        return jdbcTemplate.queryForList("select status from push_log order by id", String.class);
    }
}
