package com.star_pick.starpick.domain.ad.infrastructure.tink;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.apps.rewardedads.RewardedAdsVerifier;
import com.star_pick.starpick.domain.ad.exception.AdRewardCallbackSignatureException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 진짜 키로 서명한 fixture 로 Tink 통합을 검증한다. {@code AdRewardCallbackVerifier} 의 업무 로직
 * 테스트는 {@code FakeAdRewardCallbackVerifier} 로 대신하지만(REWARDED_AD_SSV.md §10), 서명 검증
 * 자체는 대역만으로 증명되지 않으므로 여기서 실제 {@code RewardedAdsVerifier} 를 쓴다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다 — 순수 단위 테스트라 빠르고 메모리를 적게 쓴다.
 */
class TinkAdRewardCallbackVerifierTest {

    private static final long KEY_ID = 1L;

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String sign(PrivateKey privateKey, Map<String, String> params) throws Exception {
        String message = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        String signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        return message + "&signature=" + signatureBase64Url + "&key_id=" + KEY_ID;
    }

    private static Map<String, String> sampleParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ad_network", "5450213213286189855");
        params.put("ad_unit", "ca-app-pub-3940256099942544/5224354917");
        params.put("custom_data", "11111111-1111-1111-1111-111111111111");
        params.put("reward_amount", "2");
        params.put("reward_item", "recipe_slot");
        params.put("timestamp", "1757980800000");
        params.put("transaction_id", "txn-abc-123");
        return params;
    }

    @Test
    void verifiesASignatureSignedByTheMatchingKey() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String query = sign(keyPair.getPrivate(), sampleParams());

        RewardedAdsVerifier tinkVerifier = new RewardedAdsVerifier.Builder()
                .addVerifyingPublicKey(KEY_ID, (ECPublicKey) keyPair.getPublic())
                .build();
        TinkAdRewardCallbackVerifier verifier = new TinkAdRewardCallbackVerifier(tinkVerifier);

        assertThatCode(() -> verifier.verify(query)).doesNotThrowAnyException();
    }

    @Test
    void rejectsATamperedParameter() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String query = sign(keyPair.getPrivate(), sampleParams());
        // 서명 이후 값을 바꾼다 — 서명된 메시지와 실제 파라미터가 달라진다.
        String tampered = query.replace("reward_amount=2", "reward_amount=99");

        RewardedAdsVerifier tinkVerifier = new RewardedAdsVerifier.Builder()
                .addVerifyingPublicKey(KEY_ID, (ECPublicKey) keyPair.getPublic())
                .build();
        TinkAdRewardCallbackVerifier verifier = new TinkAdRewardCallbackVerifier(tinkVerifier);

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(AdRewardCallbackSignatureException.class);
    }

    @Test
    void rejectsASignatureFromAnUntrustedKey() throws Exception {
        KeyPair signingKeyPair = generateKeyPair();
        KeyPair trustedKeyPair = generateKeyPair();
        String query = sign(signingKeyPair.getPrivate(), sampleParams());

        // key_id 는 같지만(1L) 검증기에는 다른 공개키를 등록한다 — 서명자가 신뢰하는 키가 아니다.
        RewardedAdsVerifier tinkVerifier = new RewardedAdsVerifier.Builder()
                .addVerifyingPublicKey(KEY_ID, (ECPublicKey) trustedKeyPair.getPublic())
                .build();
        TinkAdRewardCallbackVerifier verifier = new TinkAdRewardCallbackVerifier(tinkVerifier);

        assertThatThrownBy(() -> verifier.verify(query))
                .isInstanceOf(AdRewardCallbackSignatureException.class);
    }

    @Test
    void rejectsAnUnknownKeyId() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String query = sign(keyPair.getPrivate(), sampleParams());

        RewardedAdsVerifier tinkVerifier = new RewardedAdsVerifier.Builder()
                // 등록된 key_id 가 2L 뿐이라 서명의 key_id=1 을 찾지 못한다.
                .addVerifyingPublicKey(2L, (ECPublicKey) keyPair.getPublic())
                .build();
        TinkAdRewardCallbackVerifier verifier = new TinkAdRewardCallbackVerifier(tinkVerifier);

        assertThatThrownBy(() -> verifier.verify(query))
                .isInstanceOf(AdRewardCallbackSignatureException.class);
    }

    @Test
    void rejectsMalformedQueryMissingSignature() throws Exception {
        RewardedAdsVerifier tinkVerifier = new RewardedAdsVerifier.Builder()
                .setVerifyingPublicKeys("{\"keys\":[]}")
                .build();
        TinkAdRewardCallbackVerifier verifier = new TinkAdRewardCallbackVerifier(tinkVerifier);

        assertThatThrownBy(() -> verifier.verify("ad_unit=x&custom_data=y"))
                .isInstanceOf(AdRewardCallbackSignatureException.class);
    }
}
