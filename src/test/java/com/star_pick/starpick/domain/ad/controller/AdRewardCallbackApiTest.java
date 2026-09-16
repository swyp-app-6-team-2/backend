package com.star_pick.starpick.domain.ad.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.support.FakeAdRewardCallbackVerifier;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "AdMob SSV 콜백" 통합 테스트. REWARDED_AD_SSV.md §4.5.
 *
 * <p>서명 검증 자체는 {@code FakeAdRewardCallbackVerifier} 로 대신한다 — 진짜 Tink 서명 검증은
 * {@code TinkAdRewardCallbackVerifierTest} 가 따로 본다(§10). 여기서는 세션·계정·잠금·지급 업무
 * 로직만 확인한다.
 */
@IntegrationTest
class AdRewardCallbackApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 998001L;
    private static final String AD_UNIT = "ca-app-pub-3940256099942544/5224354917";
    private static final String PATH = "/api/v1/ads/rewards/callback";

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeAdRewardCallbackVerifier verifier;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
    }

    @AfterEach
    void restore() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }

    private UUID seedSession(String status, Instant createdAt, Instant expiresAt, Instant verificationDeadline) {
        // PostgreSQL JDBC 드라이버가 Instant 의 SQL 타입을 추론하지 못해 OffsetDateTime 으로 바꿔 넘긴다
        // (DueNotificationRecorder 와 같은 이유).
        return jdbc.queryForObject("""
                insert into ad_reward_session
                    (id, user_id, request_id, platform, expected_ad_unit, reward_type, reward_amount, quota_date,
                     status, created_at, expires_at, verification_deadline)
                values (gen_random_uuid(), ?, ?, 'ANDROID', ?, 'recipe_slot', 2, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, OWNER, UUID.randomUUID().toString(), AD_UNIT, LocalDate.now(SEOUL), status,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(verificationDeadline, ZoneOffset.UTC));
    }

    private void seedQuota(int granted, int reserved) {
        jdbc.update("""
                insert into ad_reward_daily_quota (user_id, quota_date, granted_count, reserved_count)
                values (?, ?, ?, ?)
                """, OWNER, LocalDate.now(SEOUL), granted, reserved);
    }

    private Map<String, String> validParams(UUID sessionId, String transactionId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ad_unit", AD_UNIT);
        params.put("custom_data", sessionId.toString());
        params.put("reward_amount", "2");
        params.put("reward_item", "recipe_slot");
        params.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
        params.put("transaction_id", transactionId);
        params.put("signature", "dummy-signature");
        params.put("key_id", "1");
        return params;
    }

    /**
     * {@code MockMvc} 의 {@code .param(...)} 은 {@code getParameterMap()} 만 채우고
     * {@code getQueryString()} 은 비워 둔다. 컨트롤러가 서명 검증에 원문 query string 을 쓰므로
     * URL 에 직접 쿼리를 붙여 {@code getQueryString()} 도 채워지게 한다.
     *
     * <p>값을 직접 URL-encode 하지 않는다 — {@code get(String)} 이 템플릿을 자체적으로 한 번 더
     * encode 하므로 미리 encode 하면 {@code "/"} 같은 문자가 이중 인코딩된다(실측).
     */
    private ResultActions callCallback(Map<String, String> params) throws Exception {
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return mvc.perform(get(PATH + "?" + query));
    }

    private int quotaGranted() {
        return jdbc.queryForObject(
                "select granted_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER);
    }

    private int quotaReserved() {
        return jdbc.queryForObject(
                "select reserved_count from ad_reward_daily_quota where user_id = ?", Integer.class, OWNER);
    }

    private String sessionStatus(UUID sessionId) {
        return jdbc.queryForObject(
                "select status from ad_reward_session where id = ?", String.class, sessionId);
    }

    private int transactionCount(String transactionId) {
        return jdbc.queryForObject(
                "select count(*) from ad_reward_transaction where transaction_id = ?", Integer.class,
                transactionId);
    }

    private String transactionStatus(String transactionId) {
        return jdbc.queryForObject(
                "select status from ad_reward_transaction where transaction_id = ?", String.class, transactionId);
    }

    private String transactionReasonCode(String transactionId) {
        return jdbc.queryForObject(
                "select reason_code from ad_reward_transaction where transaction_id = ?", String.class,
                transactionId);
    }

    private int recipeSlotLimit() {
        return jdbc.queryForObject(
                "select recipe_slot_limit from users where user_id = ?", Integer.class, OWNER);
    }

    @Test
    @DisplayName("검증을 통과하면 슬롯을 지급하고 세션·일일 상태·거래를 함께 확정한다")
    void grantsOnValidCallback() throws Exception {
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));
        int slotBefore = recipeSlotLimit();

        callCallback(validParams(sessionId, "txn-grant-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        assertThat(sessionStatus(sessionId)).isEqualTo("GRANTED");
        assertThat(quotaGranted()).isEqualTo(1);
        assertThat(quotaReserved()).isZero();
        assertThat(recipeSlotLimit()).isEqualTo(slotBefore + 2);
        assertThat(transactionCount("txn-grant-1")).isOne();
        assertThat(transactionStatus("txn-grant-1")).isEqualTo("GRANTED");
    }

    @Test
    @DisplayName("이미 처리한 거래 ID는 다시 지급하지 않는다")
    void duplicateTransactionIdDoesNotGrantAgain() throws Exception {
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));
        Map<String, String> params = validParams(sessionId, "txn-dup-1");

        callCallback(params).andExpect(status().isOk());
        int slotAfterFirst = recipeSlotLimit();

        callCallback(params).andExpect(status().isOk()).andExpect(content().string(""));

        assertThat(recipeSlotLimit()).isEqualTo(slotAfterFirst);
        assertThat(quotaGranted()).isEqualTo(1);
        assertThat(transactionCount("txn-dup-1")).isOne();
    }

    @Test
    @DisplayName("이미 지급된 세션에 다른 거래 ID로 다시 오면 거절 이력만 남고 재지급하지 않는다")
    void differentTransactionIdForAlreadyGrantedSessionIsRejected() throws Exception {
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));
        callCallback(validParams(sessionId, "txn-first")).andExpect(status().isOk());
        int slotAfterFirst = recipeSlotLimit();

        callCallback(validParams(sessionId, "txn-second"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        assertThat(recipeSlotLimit()).isEqualTo(slotAfterFirst);
        assertThat(quotaGranted()).isEqualTo(1);
        assertThat(transactionStatus("txn-second")).isEqualTo("REJECTED");
        assertThat(transactionReasonCode("txn-second")).isEqualTo("SESSION_ALREADY_GRANTED");
    }

    @Test
    @DisplayName("세션의 기대 광고 단위와 다르면 거절하고 세션은 PENDING을 유지한다")
    void rejectsAdUnitMismatch() throws Exception {
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));
        Map<String, String> params = validParams(sessionId, "txn-adunit");
        params.put("ad_unit", "ca-app-pub-0000000000000000/0000000000");

        callCallback(params).andExpect(status().isOk()).andExpect(content().string(""));

        assertThat(sessionStatus(sessionId)).isEqualTo("PENDING");
        assertThat(quotaReserved()).isEqualTo(1);
        assertThat(quotaGranted()).isZero();
        assertThat(transactionStatus("txn-adunit")).isEqualTo("REJECTED");
        assertThat(transactionReasonCode("txn-adunit")).isEqualTo("AD_UNIT_MISMATCH");
    }

    @Test
    @DisplayName("reward_item이 정책과 다르면 거절한다")
    void rejectsRewardItemMismatch() throws Exception {
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));
        Map<String, String> params = validParams(sessionId, "txn-item");
        params.put("reward_item", "coins");

        callCallback(params).andExpect(status().isOk());

        assertThat(transactionReasonCode("txn-item")).isEqualTo("REWARD_ITEM_MISMATCH");
        assertThat(quotaGranted()).isZero();
    }

    @Test
    @DisplayName("reward_amount가 세션에 기록된 값과 다르면 거절한다")
    void rejectsRewardAmountMismatch() throws Exception {
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));
        Map<String, String> params = validParams(sessionId, "txn-amount");
        params.put("reward_amount", "5");

        callCallback(params).andExpect(status().isOk());

        assertThat(transactionReasonCode("txn-amount")).isEqualTo("REWARD_AMOUNT_MISMATCH");
        assertThat(quotaGranted()).isZero();
    }

    @Test
    @DisplayName("custom_data가 어떤 세션과도 맞지 않으면 세션·사용자 없이 거절 이력만 남긴다")
    void rejectsUnknownSession() throws Exception {
        Map<String, String> params = validParams(UUID.randomUUID(), "txn-orphan");

        callCallback(params).andExpect(status().isOk()).andExpect(content().string(""));

        assertThat(transactionStatus("txn-orphan")).isEqualTo("REJECTED");
        assertThat(transactionReasonCode("txn-orphan")).isEqualTo("SESSION_NOT_FOUND");
        assertThat(jdbc.queryForObject(
                "select session_id is null and user_id is null from ad_reward_transaction where transaction_id = ?",
                Boolean.class, "txn-orphan")).isTrue();
    }

    @Test
    @DisplayName("검증 수신 마감이 지나면 지급 없이 세션을 만료시키고 예약을 반환한다")
    void expiresSessionPastVerificationDeadline() throws Exception {
        seedQuota(0, 1);
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID sessionId = seedSession("PENDING", past.minusSeconds(1800), past.minusSeconds(1700), past);
        Map<String, String> params = validParams(sessionId, "txn-late");
        // 이벤트 시각 자체는 세션의 원래 유효창 안에 있어야 "지연 콜백"의 뜻이 산다.
        params.put("timestamp", String.valueOf(past.minusSeconds(1750).toEpochMilli()));

        callCallback(params).andExpect(status().isOk());

        assertThat(sessionStatus(sessionId)).isEqualTo("EXPIRED");
        assertThat(quotaReserved()).isZero();
        assertThat(quotaGranted()).isZero();
        assertThat(transactionReasonCode("txn-late")).isEqualTo("VERIFICATION_DEADLINE_PASSED");
    }

    @Test
    @DisplayName("탈퇴한 사용자는 지급하지 않고 세션은 PENDING을 유지한다")
    void rejectsInactiveAccount() throws Exception {
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));
        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);

        callCallback(validParams(sessionId, "txn-inactive")).andExpect(status().isOk());

        assertThat(transactionReasonCode("txn-inactive")).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(sessionStatus(sessionId)).isEqualTo("PENDING");
        assertThat(quotaReserved()).isEqualTo(1);
    }

    @Test
    @DisplayName("서명이 유효하지 않으면 400이고 지급도 거래 기록도 없다")
    void invalidSignatureReturns400WithoutRecording() throws Exception {
        verifier.behave(FakeAdRewardCallbackVerifier.Behavior.INVALID_SIGNATURE);
        seedQuota(0, 1);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));

        callCallback(validParams(sessionId, "txn-badsig"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        assertThat(transactionCount("txn-badsig")).isZero();
        assertThat(sessionStatus(sessionId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("공개키 조회 실패 등 일시 장애는 503이고 성공 처리로 삼지 않는다")
    void verifierUnavailableReturns503WithoutRecording() throws Exception {
        verifier.behave(FakeAdRewardCallbackVerifier.Behavior.UNAVAILABLE);
        UUID sessionId = seedSession("PENDING", Instant.now(), Instant.now().plusSeconds(1800),
                Instant.now().plus(1, ChronoUnit.DAYS));

        callCallback(validParams(sessionId, "txn-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));

        assertThat(transactionCount("txn-unavailable")).isZero();
    }

    @Test
    @DisplayName("파라미터가 없거나 구문이 잘못되면 400이다")
    void malformedParametersReturn400() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isBadRequest());
    }
}
