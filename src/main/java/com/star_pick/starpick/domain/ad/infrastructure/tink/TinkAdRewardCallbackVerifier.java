package com.star_pick.starpick.domain.ad.infrastructure.tink;

import com.google.crypto.tink.apps.rewardedads.RewardedAdsVerifier;
import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackSignatureException;
import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackVerifierUnavailableException;
import com.star_pick.starpick.domain.ad.service.AdRewardCallbackVerifier;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Tink {@code RewardedAdsVerifier} 어댑터. REWARDED_AD_SSV.md §5.
 *
 * <p><b>서명 불일치와 공개키 다운로드 장애가 같은 예외 타입으로 온다(1.14.0 실측).</b>
 * {@code Builder.fetchVerifyingPublicKeysWith(downloader)} 가 만드는 provider 는 다운로드가
 * {@link IOException} 으로 실패하면 {@code new GeneralSecurityException("Failed to fetch keys!", io)}
 * 로 감싼다({@code RewardedAdsVerifier$Builder$1.get()} 역어셈블 확인). 메시지 문자열은 라이브러리
 * 내부 구현이라 버전이 바뀌면 달라질 수 있으므로, 대신 원인이 {@link IOException} 인지로 두 경우를
 * 가른다 — 이 편이 더 안정적인 신호다.
 *
 * <p><b>{@code verify(String)} 는 인자를 {@code new URI(arg).getQuery()} 로 다시 파싱한다</b>(역어셈블
 * 확인). 앞에 {@code "?"} 가 없는 순수 query string(= {@code HttpServletRequest.getQueryString()}
 * 그대로)을 넘기면 {@code URI} 가 이를 경로로 해석해 {@code getQuery()} 가 {@code null} 을 돌려주고
 * 서명이 항상 실패한다. 그래서 여기서 {@code "?"} 를 붙여 query 부분으로 명시적으로 파싱되게 한다 —
 * {@link AdRewardCallbackVerifier} 인터페이스 계약 자체는 "원문 query string"을 그대로 유지해 이
 * 어댑터 하나만 Tink 의 파싱 방식에 맞춘다.
 */
public class TinkAdRewardCallbackVerifier implements AdRewardCallbackVerifier {

    private final RewardedAdsVerifier verifier;

    public TinkAdRewardCallbackVerifier(RewardedAdsVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void verify(String rawQueryString) {
        try {
            verifier.verify("?" + rawQueryString);
        } catch (GeneralSecurityException e) {
            if (e.getCause() instanceof IOException) {
                throw new AdRewardCallbackVerifierUnavailableException("AdMob 공개키 조회에 실패했다.", e);
            }
            throw new AdRewardCallbackSignatureException("AdMob SSV 서명 검증에 실패했다.", e);
        }
    }
}
