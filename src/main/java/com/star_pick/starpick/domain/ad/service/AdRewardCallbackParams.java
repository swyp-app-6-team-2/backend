package com.star_pick.starpick.domain.ad.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SSV 콜백 query 파라미터 중 업무 검증에 쓰는 값만 뽑아 검증한다. REWARDED_AD_SSV.md §5.3.
 *
 * <p>서명 검증({@code AdRewardCallbackVerifier})과 이 파싱이 서로 다른 값을 보면 안 되므로, 둘 다
 * 같은 {@code HttpServletRequest} 에서 얻는다 — 원문 query string(서명용)과
 * {@code getParameterMap()}(파싱용)은 같은 요청의 같은 파라미터를 가리킨다.
 *
 * <p>순수 클래스다. Spring 컨텍스트 없이 파싱 규칙만 단위 테스트한다.
 */
record AdRewardCallbackParams(
        UUID sessionId,
        String adUnit,
        int rewardAmount,
        String rewardItem,
        Instant eventTime,
        String transactionId) {

    private static final int MAX_LENGTH = 255;

    /** @throws IllegalArgumentException 필수 값 누락·중복 파라미터·빈 값·길이 초과·숫자 변환 및 범위 오류 */
    static AdRewardCallbackParams parse(Map<String, String[]> queryParams) {
        String customData = single(queryParams, "custom_data");
        String adUnit = single(queryParams, "ad_unit");
        String rewardAmountRaw = single(queryParams, "reward_amount");
        String rewardItem = single(queryParams, "reward_item");
        String timestampRaw = single(queryParams, "timestamp");
        String transactionId = single(queryParams, "transaction_id");

        UUID sessionId;
        try {
            sessionId = UUID.fromString(customData);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("custom_data가 세션 id 형식이 아니다: " + customData, e);
        }

        int rewardAmount;
        try {
            rewardAmount = Integer.parseInt(rewardAmountRaw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("reward_amount가 숫자가 아니다: " + rewardAmountRaw, e);
        }
        if (rewardAmount < 0) {
            throw new IllegalArgumentException("reward_amount가 음수다: " + rewardAmount);
        }

        long timestampMillis;
        try {
            timestampMillis = Long.parseLong(timestampRaw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timestamp가 숫자가 아니다: " + timestampRaw, e);
        }
        if (timestampMillis <= 0) {
            throw new IllegalArgumentException("timestamp가 올바른 epoch millis가 아니다: " + timestampMillis);
        }

        return new AdRewardCallbackParams(sessionId, adUnit, rewardAmount, rewardItem,
                Instant.ofEpochMilli(timestampMillis), transactionId);
    }

    private static String single(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException(name + " 파라미터가 없다.");
        }
        if (values.length > 1) {
            throw new IllegalArgumentException(name + " 파라미터가 중복됐다.");
        }
        String value = values[0];
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 파라미터가 비어 있다.");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(name + " 파라미터가 너무 길다.");
        }
        return value;
    }
}
