package com.star_pick.starpick.domain.ad.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 광고 보상 세션 만료 주기 작업. {@code IngestionMaintenanceTest} 와 같은 방식으로
 * {@code @Scheduled} 를 거치지 않고 {@link AdRewardMaintenance} 를 직접 호출한다 — 테스트가
 * 실제 시간을 기다릴 필요가 없다.
 */
@IntegrationTest
class AdRewardMaintenanceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 999701L;
    private static final long OTHER_USER = 999702L;
    private static final String AD_UNIT = "ca-app-pub-3940256099942544/5224354917";

    @Autowired
    private AdRewardMaintenance maintenance;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER_USER);
    }

    private void seedQuota(long userId, LocalDate quotaDate, int granted, int reserved) {
        jdbc.update("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, ?, ?, ?)
                """, userId, quotaDate, granted, reserved);
    }

    /** {@code verificationDeadline} 을 직접 지정해 이미 지났거나 아직 남은 세션을 만든다. */
    private UUID seedSession(long userId, LocalDate quotaDate, String status, Instant verificationDeadline) {
        Instant createdAt = verificationDeadline.minusSeconds(3600);
        return jdbc.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_type, reward_amount, quota_date,
                     status, created_at, expires_at, verification_deadline)
                values (gen_random_uuid(), ?, ?, 'ANDROID', ?, 'recipe_slot', 2, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, userId, UUID.randomUUID().toString(), AD_UNIT, quotaDate, status,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(createdAt.plusSeconds(1800), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(verificationDeadline, ZoneOffset.UTC));
    }

    private String sessionStatus(UUID sessionId) {
        return jdbc.queryForObject("select status from ad_reward_session where id = ?", String.class, sessionId);
    }

    private String reasonCode(UUID sessionId) {
        return jdbc.queryForObject("select reason_code from ad_reward_session where id = ?", String.class,
                sessionId);
    }

    private int reservedCount(long userId, LocalDate quotaDate) {
        return jdbc.queryForObject("""
                select reserved_count from ad_reward_daily_quota where user_id = ? and quota_date = ?
                """, Integer.class, userId, quotaDate);
    }

    @Test
    @DisplayName("검증 수신 마감이 지난 PENDING 세션을 EXPIRED로 바꾸고 예약을 반환한다")
    void expiresOverdueSessionAndReleasesReservation() {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 0, 1);
        UUID sessionId = seedSession(OWNER, today, "PENDING", Instant.now().minusSeconds(60));

        maintenance.expireOverdueSessions();

        assertThat(sessionStatus(sessionId)).isEqualTo("EXPIRED");
        assertThat(reasonCode(sessionId)).isEqualTo("VERIFICATION_DEADLINE_PASSED");
        assertThat(reservedCount(OWNER, today)).isZero();
    }

    @Test
    @DisplayName("검증 수신 마감 전인 PENDING 세션은 건드리지 않는다")
    void doesNotTouchSessionsStillWithinDeadline() {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 0, 1);
        UUID sessionId = seedSession(OWNER, today, "PENDING", Instant.now().plusSeconds(3600));

        maintenance.expireOverdueSessions();

        assertThat(sessionStatus(sessionId)).isEqualTo("PENDING");
        assertThat(reservedCount(OWNER, today)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 확정된 세션은 다시 건드리지 않는다")
    void doesNotTouchAlreadyTerminalSessions() {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 1, 0);
        UUID grantedId = seedSession(OWNER, today, "GRANTED", Instant.now().minusSeconds(60));

        maintenance.expireOverdueSessions();

        assertThat(sessionStatus(grantedId)).isEqualTo("GRANTED");
        assertThat(reservedCount(OWNER, today)).isZero();
    }

    @Test
    @DisplayName("반복 실행해도 예약을 두 번 반환하지 않는다")
    void repeatedRunsDoNotDoubleRelease() {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 0, 1);
        seedSession(OWNER, today, "PENDING", Instant.now().minusSeconds(60));

        maintenance.expireOverdueSessions();
        maintenance.expireOverdueSessions();
        maintenance.expireOverdueSessions();

        assertThat(reservedCount(OWNER, today)).isZero();
    }

    @Test
    @DisplayName("전날 예약 정리가 오늘 횟수를 바꾸지 않는다")
    void previousDayCleanupDoesNotAffectTodayCounters() {
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate yesterday = today.minusDays(1);
        seedQuota(OWNER, yesterday, 0, 1);
        seedQuota(OWNER, today, 1, 1);
        UUID staleSessionId = seedSession(OWNER, yesterday, "PENDING", Instant.now().minusSeconds(60));

        maintenance.expireOverdueSessions();

        assertThat(sessionStatus(staleSessionId)).isEqualTo("EXPIRED");
        assertThat(reservedCount(OWNER, yesterday)).isZero();
        assertThat(reservedCount(OWNER, today)).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 사용자의 세션을 섞지 않는다")
    void handlesMultipleUsersIndependently() {
        LocalDate today = LocalDate.now(SEOUL);
        seedQuota(OWNER, today, 0, 1);
        seedQuota(OTHER_USER, today, 0, 1);
        UUID ownerSession = seedSession(OWNER, today, "PENDING", Instant.now().minusSeconds(60));
        UUID otherSession = seedSession(OTHER_USER, today, "PENDING", Instant.now().plusSeconds(3600));

        maintenance.expireOverdueSessions();

        assertThat(sessionStatus(ownerSession)).isEqualTo("EXPIRED");
        assertThat(reservedCount(OWNER, today)).isZero();
        assertThat(sessionStatus(otherSession)).isEqualTo("PENDING");
        assertThat(reservedCount(OTHER_USER, today)).isEqualTo(1);
    }
}
