package com.star_pick.starpick.domain.ad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 순수 파싱 규칙만 검증한다. Spring 컨텍스트가 필요 없다. */
class AdRewardCallbackParamsTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private Map<String, String[]> validParams() {
        Map<String, String[]> params = new HashMap<>();
        params.put("custom_data", new String[]{SESSION_ID.toString()});
        params.put("ad_unit", new String[]{"ca-app-pub-3940256099942544/5224354917"});
        params.put("reward_amount", new String[]{"2"});
        params.put("reward_item", new String[]{"recipe_slot"});
        params.put("timestamp", new String[]{"1757980800000"});
        params.put("transaction_id", new String[]{"txn-1"});
        return params;
    }

    @Test
    void parsesAllFields() {
        AdRewardCallbackParams parsed = AdRewardCallbackParams.parse(validParams());

        assertThat(parsed.sessionId()).isEqualTo(SESSION_ID);
        assertThat(parsed.adUnit()).isEqualTo("ca-app-pub-3940256099942544/5224354917");
        assertThat(parsed.rewardAmount()).isEqualTo(2);
        assertThat(parsed.rewardItem()).isEqualTo("recipe_slot");
        assertThat(parsed.eventTime()).isEqualTo(Instant.ofEpochMilli(1757980800000L));
        assertThat(parsed.transactionId()).isEqualTo("txn-1");
    }

    @Test
    void rejectsMissingParameter() {
        Map<String, String[]> params = validParams();
        params.remove("ad_unit");

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ad_unit");
    }

    @Test
    void rejectsDuplicateParameter() {
        Map<String, String[]> params = validParams();
        params.put("reward_amount", new String[]{"2", "3"});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reward_amount");
    }

    @Test
    void rejectsBlankParameter() {
        Map<String, String[]> params = validParams();
        params.put("transaction_id", new String[]{"  "});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction_id");
    }

    @Test
    void rejectsParameterOverLengthLimit() {
        Map<String, String[]> params = validParams();
        params.put("transaction_id", new String[]{"x".repeat(256)});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction_id");
    }

    @Test
    void rejectsNonNumericRewardAmount() {
        Map<String, String[]> params = validParams();
        params.put("reward_amount", new String[]{"two"});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reward_amount");
    }

    @Test
    void rejectsNegativeRewardAmount() {
        Map<String, String[]> params = validParams();
        params.put("reward_amount", new String[]{"-1"});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reward_amount");
    }

    @Test
    void rejectsNonNumericTimestamp() {
        Map<String, String[]> params = validParams();
        params.put("timestamp", new String[]{"not-a-number"});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void rejectsZeroOrNegativeTimestamp() {
        Map<String, String[]> params = validParams();
        params.put("timestamp", new String[]{"0"});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void rejectsMalformedCustomData() {
        Map<String, String[]> params = validParams();
        params.put("custom_data", new String[]{"not-a-uuid"});

        assertThatThrownBy(() -> AdRewardCallbackParams.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom_data");
    }
}
