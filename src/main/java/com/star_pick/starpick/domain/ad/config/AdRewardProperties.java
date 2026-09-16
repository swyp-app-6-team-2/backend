package com.star_pick.starpick.domain.ad.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 보상형 광고 지급 정책. REWARDED_AD_SSV.md §2, §2.1, §9.
 *
 * <p>{@code sessionValidity}·{@code verificationDeadline}·{@code clockSkewTolerance} 는 기술
 * 문서가 명시한 제안값이며 프런트와 공유 후 운영 설정으로 관리한다. 일일 한도는
 * {@code ck_ad_reward_daily_quota_limit} DB CHECK 와 값을 맞춘다 — 바꿀 때 함께 바꾼다.
 */
@ConfigurationProperties("ad-reward")
public record AdRewardProperties(
        int dailyLimit,
        int rewardAmount,
        String rewardType,
        Duration sessionValidity,
        Duration verificationDeadline,
        Duration clockSkewTolerance,
        Platform android,
        Platform ios,
        External external,
        Callback callback) {

    /**
     * 플랫폼별 광고 설정.
     *
     * @param adUnitId               앱이 광고를 로드할 때 쓰는 광고 단위.
     * @param expectedCallbackAdUnit SSV 콜백의 {@code ad_unit} 과 대조할 기대값. SDK 표기와 콜백
     *                               표기가 실제로 같은지는 AdMob 테스트로 확인해야 한다(§5.6) —
     *                               확인 전까지는 {@code adUnitId} 와 같은 값을 쓴다.
     */
    public record Platform(String adUnitId, String expectedCallbackAdUnit) {
    }

    /** false 면 실제 SSV 검증기 Bean 을 만들지 않는다. {@code GeminiConfig} 의 {@code external.enabled} 와 같은 테스트 스위치다. */
    public record External(boolean enabled) {
    }

    /** true 면 AdMob SSV 테스트 도구가 서명한 콜백을 검증한다(§5, verifier-keys-test.json 실측). */
    public record Callback(boolean useTestVerifyingKeys) {
    }
}
