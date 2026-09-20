package com.star_pick.starpick.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class NotificationSchemaTest {

    private static final long USER_ID = 1L;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(USER_ID);
    }

    @Test
    @DisplayName("세 테이블의 컬럼과 null 허용이 계약과 같다")
    void columns() {
        assertThat(columns("notification_setting"))
                .containsExactly("id", "user_id", "enabled", "weekdays", "time_slots");
        assertThat(columns("push_token")).containsExactly("id", "user_id", "token", "platform", "active");
        assertThat(columns("push_log"))
                .containsExactly("id", "user_id", "push_token_id", "scheduled_at", "status", "opened_at");
        assertThat(nullable("push_log")).containsExactly("opened_at");
        assertThat(nullable("notification_setting")).isEmpty();
        assertThat(nullable("push_token")).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL 전용 타입을 쓴다")
    void types() {
        assertThat(attr("notification_setting", "weekdays", "udt_name")).isEqualTo("_text");
        assertThat(attr("notification_setting", "time_slots", "udt_name")).isEqualTo("jsonb");
        assertThat(attr("push_token", "token", "data_type")).isEqualTo("text");
        assertThat(attr("push_log", "scheduled_at", "data_type")).isEqualTo("timestamp with time zone");
        assertThat(attr("push_log", "opened_at", "data_type")).isEqualTo("timestamp with time zone");
    }

    @Test
    @DisplayName("UNIQUE·FK 제약이 이름대로 존재한다")
    void constraints() {
        assertThat(constraintNames()).contains(
                "uk_notification_setting_user_id", "fk_notification_setting_user",
                "uk_push_token_token", "fk_push_token_user",
                "uk_push_log_token_scheduled_at", "fk_push_log_user", "fk_push_log_push_token");
        assertThat(deleteRule("fk_push_log_push_token")).isEqualTo("CASCADE");
        assertThat(deleteRule("fk_push_log_user")).isEqualTo("NO ACTION");
    }

    /**
     * 토큰은 다른 사용자가 등록하면 행 id 를 유지한 채 주인만 바뀐다. 그래서 로그 주인과 토큰 주인이
     * 어긋날 수 있고, 그 상태로 토큰을 지우면 탈퇴가 FK 위반으로 실패했다(이슈 #126).
     */
    @Test
    @DisplayName("푸시 토큰을 지우면 다른 사용자의 발송 기록도 함께 지워진다")
    void deletingTokenCascadesToLogsOfPreviousOwner() {
        long previousOwner = USER_ID;
        Long tokenId = jdbcTemplate.queryForObject("""
                insert into push_token (user_id, token, platform, active)
                values (?, 'shared-device', 'IOS', true) returning id
                """, Long.class, previousOwner);
        jdbcTemplate.update("""
                insert into push_log (user_id, push_token_id, scheduled_at, status)
                values (?, ?, timestamptz '2026-09-20 03:00:00+00', 'SENT')
                """, previousOwner, tokenId);

        jdbcTemplate.update("delete from push_token where id = ?", tokenId);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from push_log where push_token_id = ?", Integer.class, tokenId)).isZero();
    }

    @Test
    @DisplayName("사용자당 설정 1개, 토큰 전역 1개, 토큰·분당 기록 1개")
    void uniqueness() {
        jdbcTemplate.update("insert into notification_setting (user_id, enabled) values (?, false)", USER_ID);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into notification_setting (user_id, enabled) values (?, false)", USER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        Long tokenId = jdbcTemplate.queryForObject("""
                insert into push_token (user_id, token, platform, active) values (?, 'tok', 'IOS', true) returning id
                """, Long.class, USER_ID);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into push_token (user_id, token, platform, active) values (?, 'tok', 'ANDROID', true)", USER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        String insertLog = """
                insert into push_log (user_id, push_token_id, scheduled_at, status)
                values (?, ?, timestamptz '2026-09-14 03:00:00+00', 'PROCESSING')
                """;
        jdbcTemplate.update(insertLog, USER_ID, tokenId);
        assertThatThrownBy(() -> jdbcTemplate.update(insertLog, USER_ID, tokenId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("요일은 MONDAY~SUNDAY 이름만 허용한다")
    void weekdayCheck() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into notification_setting (user_id, enabled, weekdays) values (?, false, '{MON}')", USER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> columns(String table) {
        return jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = ? order by ordinal_position
                """, String.class, table);
    }

    private List<String> nullable(String table) {
        return jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = ? and is_nullable = 'YES'
                order by ordinal_position
                """, String.class, table);
    }

    private String attr(String table, String column, String attribute) {
        return jdbcTemplate.queryForObject("""
                select %s from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """.formatted(attribute), String.class, table, column);
    }

    private String deleteRule(String constraint) {
        return jdbcTemplate.queryForObject("""
                select delete_rule from information_schema.referential_constraints
                where constraint_schema = 'public' and constraint_name = ?
                """, String.class, constraint);
    }

    private List<String> constraintNames() {
        return jdbcTemplate.queryForList("""
                select constraint_name from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name in ('notification_setting', 'push_token', 'push_log')
                """, String.class);
    }
}
