package com.star_pick.starpick.domain.ad.service;

import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackSignatureException;
import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackVerifierUnavailableException;

/**
 * AdMob SSV 원문 query string 서명 검증. REWARDED_AD_SSV.md §5.
 *
 * <p>Bean 으로 주입해 테스트가 대역으로 바꿀 수 있게 한다(§5.2). 구현체는
 * {@code com.google.crypto.tink:apps-rewardedads} 의 {@code RewardedAdsVerifier} 를 감싼다.
 */
public interface AdRewardCallbackVerifier {

    /**
     * @param rawQueryString 파라미터 순서를 바꾸지 않은 원문 query string. {@code signature}·{@code key_id}
     *                       가 마지막 두 파라미터여야 한다 — Google 이 그 순서로 보내므로 그대로 넘기면 된다.
     */
    void verify(String rawQueryString)
            throws AdRewardCallbackSignatureException, AdRewardCallbackVerifierUnavailableException;
}
