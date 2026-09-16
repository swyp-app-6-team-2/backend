package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackSignatureException;
import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackVerifierUnavailableException;
import com.star_pick.starpick.domain.ad.service.AdRewardCallbackVerifier;

/**
 * 실제 Tink 서명 검증 대신 쓴다. 기본은 항상 통과다.
 *
 * <p>서명 검증 그 자체(진짜 키로 서명한 fixture)는 {@code TinkAdRewardCallbackVerifierTest} 가
 * Spring 컨텍스트 없이 따로 본다. 여기서는 업무 로직(세션·잠금·지급) 테스트가 서명 검증 결과를
 * 마음대로 고를 수 있게 하는 것이 목적이다({@code REWARDED_AD_SSV.md} §10).
 */
public class FakeAdRewardCallbackVerifier implements AdRewardCallbackVerifier {

    public enum Behavior { VALID, INVALID_SIGNATURE, UNAVAILABLE }

    private volatile Behavior behavior = Behavior.VALID;

    /** 동시 콜백 수신을 재현하려는 테스트가 {@code verify()} 호출 중간에 끼워 넣는 동작. FakePushGateway 의 onSend 와 같은 방식이다. */
    private volatile Runnable onVerify = () -> { };

    public void behave(Behavior behavior) {
        this.behavior = behavior;
    }

    public void onVerify(Runnable action) {
        this.onVerify = action;
    }

    public void clear() {
        this.behavior = Behavior.VALID;
        this.onVerify = () -> { };
    }

    @Override
    public void verify(String rawQueryString) {
        onVerify.run();
        switch (behavior) {
            case VALID -> { }
            case INVALID_SIGNATURE ->
                    throw new AdRewardCallbackSignatureException("fake: invalid signature", null);
            case UNAVAILABLE ->
                    throw new AdRewardCallbackVerifierUnavailableException("fake: verifier unavailable", null);
        }
    }
}
