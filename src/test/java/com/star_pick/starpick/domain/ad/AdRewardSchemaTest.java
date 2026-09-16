package com.star_pick.starpick.domain.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ddl-auto: validate} 가 보지 않는 nullable·CHECK·UNIQUE·인덱스를 실제 스키마로 확인한다.
 * enum CHECK 의 값 집합 자체는 {@link com.star_pick.starpick.support.EnumCheckConstraintTest} 가 본다.
 */
@IntegrationTest
class AdRewardSchemaTest {

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
        assertThat(columns("ad_reward_daily_quota"))
                .containsExactly("id", "user_id", "quota_date", "granted_count", "reserved_count", "created_at");
        assertThat(columns("ad_reward_session")).containsExactly(
                "id", "user_id", "request_id", "platform", "expected_ad_unit", "reward_type", "reward_amount",
                "quota_date", "status", "reason_code", "created_at", "expires_at", "verification_deadline",
                "granted_at", "cancelled_at");
        assertThat(columns("ad_reward_transaction")).containsExactly(
                "id", "transaction_id", "session_id", "user_id", "status", "reason_code", "ad_unit", "reward_item",
                "received_reward_amount", "reward_event_at", "received_at", "processed_at", "granted_amount",
                "granted_at");

        assertThat(nullable("ad_reward_daily_quota")).isEmpty();
        assertThat(nullable("ad_reward_session")).containsExactlyInAnyOrder(
                "reason_code", "granted_at", "cancelled_at");
        assertThat(nullable("ad_reward_transaction")).containsExactlyInAnyOrder(
                "session_id", "user_id", "reason_code", "granted_at");
    }

    @Test
    @DisplayName("시각은 timestamptz, 세션 id 는 uuid, 집계 날짜는 date 다")
    void types() {
        for (String column : List.of("created_at", "expires_at", "verification_deadline", "granted_at",
                "cancelled_at")) {
            assertThat(attr("ad_reward_session", column, "data_type")).isEqualTo("timestamp with time zone");
        }
        for (String column : List.of("reward_event_at", "received_at", "processed_at", "granted_at")) {
            assertThat(attr("ad_reward_transaction", column, "data_type")).isEqualTo("timestamp with time zone");
        }
        assertThat(attr("ad_reward_session", "id", "udt_name")).isEqualTo("uuid");
        assertThat(attr("ad_reward_session", "quota_date", "udt_name")).isEqualTo("date");
        assertThat(attr("ad_reward_daily_quota", "quota_date", "udt_name")).isEqualTo("date");
    }

    @Test
    @DisplayName("FK·UNIQUE 제약과 조회용 인덱스가 이름대로 존재한다")
    void constraintsAndIndexesExist() {
        assertThat(constraintNames()).contains(
                "fk_ad_reward_daily_quota_user", "uk_ad_reward_daily_quota",
                "fk_ad_reward_session_user", "uk_ad_reward_session_request",
                "fk_ad_reward_transaction_session", "fk_ad_reward_transaction_user",
                "uk_ad_reward_transaction_transaction_id");

        List<String> sessionIndexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes where schemaname = 'public' and tablename = 'ad_reward_session'
                """, String.class);
        assertThat(sessionIndexes).contains("idx_ad_reward_session_user_quota_status");

        List<String> transactionIndexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes where schemaname = 'public' and tablename = 'ad_reward_transaction'
                """, String.class);
        assertThat(transactionIndexes).contains(
                "uk_ad_reward_transaction_granted_session", "idx_ad_reward_transaction_user");
        assertThat(indexDefinition("uk_ad_reward_transaction_granted_session"))
                .contains("WHERE ((status)::text = 'GRANTED'::text)");
    }

    @Test
    @DisplayName("일일 카운터는 음수가 될 수 없고 합계는 3을 넘을 수 없다")
    void dailyQuotaCounterCheck() {
        assertThatThrownBy(() -> insertQuota(USER_ID, "2026-09-16", -1, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertQuota(USER_ID, "2026-09-16", 0, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertQuota(USER_ID, "2026-09-16", 2, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertQuota(USER_ID, "2026-09-16", 2, 1)).isNotNull();
    }

    @Test
    @DisplayName("사용자·날짜별 일일 상태 행은 하나만 허용한다")
    void dailyQuotaUniquePerUserAndDate() {
        insertQuota(USER_ID, "2026-09-16", 0, 0);
        assertThatThrownBy(() -> insertQuota(USER_ID, "2026-09-16", 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("보상량은 양수만 허용한다")
    void sessionRewardAmountCheck() {
        assertThatThrownBy(() -> insertSession(USER_ID, "req-1", "ANDROID", "PENDING", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertSession(USER_ID, "req-1", "ANDROID", "PENDING", 2)).isNotNull();
    }

    @Test
    @DisplayName("사용자당 요청 ID는 멱등 키라 중복될 수 없다")
    void sessionRequestIdUniquePerUser() {
        insertSession(USER_ID, "req-1", "ANDROID", "PENDING", 2);
        assertThatThrownBy(() -> insertSession(USER_ID, "req-1", "IOS", "PENDING", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정의되지 않은 플랫폼·상태는 거절한다")
    void sessionEnumChecksRejectUnknownValues() {
        assertThatThrownBy(() -> insertSession(USER_ID, "req-1", "WEB", "PENDING", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSession(USER_ID, "req-1", "ANDROID", "DONE", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("거래 ID는 사용자와 무관하게 전역에서 유일하다")
    void transactionIdGloballyUnique() {
        UUID sessionId = insertSession(USER_ID, "req-1", "ANDROID", "PENDING", 2);
        insertGrantedTransaction("txn-1", sessionId, USER_ID, 2);
        assertThatThrownBy(() -> insertRejectedTransaction("txn-1", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("지급이면 금액이 양수이고 지급 시각이 있어야 하며, 거절이면 둘 다 비어야 한다")
    void transactionGrantedAmountCheck() {
        UUID sessionId = insertSession(USER_ID, "req-1", "ANDROID", "PENDING", 2);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into ad_reward_transaction
                    (transaction_id, session_id, user_id, status, ad_unit, reward_item,
                     received_reward_amount, reward_event_at, received_at, processed_at,
                     granted_amount, granted_at)
                values (?, ?, ?, 'GRANTED', 'unit', 'recipe_slot', 2, now(), now(), now(), 0, now())
                """, "txn-bad-1", sessionId, USER_ID))
                .as("GRANTED인데 지급량이 0")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into ad_reward_transaction
                    (transaction_id, session_id, user_id, status, ad_unit, reward_item,
                     received_reward_amount, reward_event_at, received_at, processed_at,
                     granted_amount, granted_at)
                values (?, ?, ?, 'GRANTED', 'unit', 'recipe_slot', 2, now(), now(), now(), 2, null)
                """, "txn-bad-2", sessionId, USER_ID))
                .as("GRANTED인데 지급 시각이 없음")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into ad_reward_transaction
                    (transaction_id, session_id, user_id, status, reason_code, ad_unit, reward_item,
                     received_reward_amount, reward_event_at, received_at, processed_at,
                     granted_amount, granted_at)
                values (?, ?, ?, 'REJECTED', 'AD_UNIT_MISMATCH', 'unit', 'recipe_slot', 2, now(), now(), now(), 1, null)
                """, "txn-bad-3", sessionId, USER_ID))
                .as("REJECTED인데 지급량이 0이 아님")
                .isInstanceOf(DataIntegrityViolationException.class);

        insertGrantedTransaction("txn-ok-1", sessionId, USER_ID, 2);
        insertRejectedTransaction("txn-ok-2", sessionId, USER_ID);
    }

    @Test
    @DisplayName("한 세션은 서로 다른 거래 ID가 와도 GRANTED 거래를 한 번만 가질 수 있다")
    void onlyOneGrantedTransactionPerSession() {
        UUID sessionId = insertSession(USER_ID, "req-1", "ANDROID", "PENDING", 2);

        insertGrantedTransaction("txn-1", sessionId, USER_ID, 2);
        assertThatThrownBy(() -> insertGrantedTransaction("txn-2", sessionId, USER_ID, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 세션에 거절 거래는 여러 건 남을 수 있다 — 부분 UNIQUE는 GRANTED에만 적용된다")
    void multipleRejectedTransactionsAllowedPerSession() {
        UUID sessionId = insertSession(USER_ID, "req-1", "ANDROID", "PENDING", 2);

        insertRejectedTransaction("txn-1", sessionId, USER_ID);
        insertRejectedTransaction("txn-2", sessionId, USER_ID);
    }

    @Test
    @DisplayName("세션·사용자를 특정하지 못한 콜백도 거절 이력으로 남길 수 있다")
    void transactionAllowsNullSessionAndUser() {
        insertRejectedTransaction("txn-orphan", null, null);
    }

    private Long insertQuota(long userId, String quotaDate, int granted, int reserved) {
        return jdbcTemplate.queryForObject("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, cast(? as date), ?, ?) returning id
                """, Long.class, userId, quotaDate, granted, reserved);
    }

    private UUID insertSession(long userId, String requestId, String platform, String status, int rewardAmount) {
        return jdbcTemplate.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_amount, quota_date,
                     status, expires_at, verification_deadline)
                values (gen_random_uuid(), ?, ?, ?, 'unit', ?, current_date,
                        ?, now() + interval '30 minutes', now() + interval '24 hours')
                returning id
                """, UUID.class, userId, requestId, platform, rewardAmount, status);
    }

    private void insertGrantedTransaction(String transactionId, UUID sessionId, Long userId, int grantedAmount) {
        jdbcTemplate.update("""
                insert into ad_reward_transaction
                    (transaction_id, session_id, user_id, status, ad_unit, reward_item,
                     received_reward_amount, reward_event_at, received_at, processed_at,
                     granted_amount, granted_at)
                values (?, ?, ?, 'GRANTED', 'unit', 'recipe_slot', ?, now(), now(), now(), ?, now())
                """, transactionId, sessionId, userId, grantedAmount, grantedAmount);
    }

    private void insertRejectedTransaction(String transactionId, UUID sessionId, Long userId) {
        jdbcTemplate.update("""
                insert into ad_reward_transaction
                    (transaction_id, session_id, user_id, status, reason_code, ad_unit, reward_item,
                     received_reward_amount, reward_event_at, received_at, processed_at,
                     granted_amount, granted_at)
                values (?, ?, ?, 'REJECTED', 'AD_UNIT_MISMATCH', 'unit', 'recipe_slot', 2, now(), now(), now(), 0, null)
                """, transactionId, sessionId, userId);
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

    private List<String> constraintNames() {
        return jdbcTemplate.queryForList("""
                select constraint_name from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name in ('ad_reward_daily_quota', 'ad_reward_session', 'ad_reward_transaction')
                """, String.class);
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
                String.class, indexName);
    }
}
