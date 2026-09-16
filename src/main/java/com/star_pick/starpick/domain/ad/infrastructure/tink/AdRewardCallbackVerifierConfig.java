package com.star_pick.starpick.domain.ad.infrastructure.tink;

import com.google.crypto.tink.apps.rewardedads.RewardedAdsVerifier;
import com.star_pick.starpick.domain.ad.config.AdRewardProperties;
import com.star_pick.starpick.domain.ad.service.AdRewardCallbackVerifier;
import java.security.GeneralSecurityException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSV 검증기 Bean. {@code GeminiConfig} 와 같은 구조다 — 테스트가 네트워크 없이 통과해야 하므로
 * {@code ad-reward.external.enabled=false} 면 이 설정 전체가 비활성화되고, 그때는
 * {@code FakeAdRewardCallbackVerifier}(TestcontainersConfiguration) 가 {@code AdRewardCallbackVerifier}
 * 자리를 채운다.
 *
 * <p>{@code KEYS_DOWNLOADER_INSTANCE_PROD}/{@code _TEST} 는 라이브러리가 미리 만들어 둔 공유
 * 싱글턴이라({@code Executors.newCachedThreadPool()} 하나를 공유) 직접 {@code KeysDownloader}
 * 를 새로 만들지 않는다 — 스레드 풀을 늘리지 않는다.
 *
 * <p>{@code Builder.build()} 는 공개키를 즉시 내려받지 않는다(역어셈블 확인 — provider 의
 * {@code get()} 이 {@code verify()} 호출마다 불린다). 그래서 이 Bean 생성이 기동을 네트워크에
 * 묶지 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "ad-reward.external", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdRewardCallbackVerifierConfig {

    @Bean
    AdRewardCallbackVerifier adRewardCallbackVerifier(AdRewardProperties properties) throws GeneralSecurityException {
        var downloader = properties.callback().useTestVerifyingKeys()
                ? RewardedAdsVerifier.KEYS_DOWNLOADER_INSTANCE_TEST
                : RewardedAdsVerifier.KEYS_DOWNLOADER_INSTANCE_PROD;
        RewardedAdsVerifier verifier = new RewardedAdsVerifier.Builder()
                .fetchVerifyingPublicKeysWith(downloader)
                .build();
        return new TinkAdRewardCallbackVerifier(verifier);
    }
}
