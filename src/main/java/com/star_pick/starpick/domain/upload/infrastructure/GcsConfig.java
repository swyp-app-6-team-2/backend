package com.star_pick.starpick.domain.upload.infrastructure;

import com.google.cloud.ServiceOptions;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.star_pick.starpick.domain.upload.service.ObjectStorage;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 저장소 클라이언트와 어댑터 Bean.
 *
 * <p>{@code gcs.enabled=false} 면 이 설정 전체가 비활성화된다. 테스트가 GCP 자격증명 없이
 * 통과해야 하기 때문이며(CLAUDE.md 검증 원칙), 그때는 테스트가 {@code ObjectStorage} 자리에
 * 가짜 구현을 등록한다. {@code gcs.bucket} 같은 설정값을 Bean 메서드 파라미터로만 받는 이유도
 * 같다. 필드나 {@code @Component} 로 받으면 비활성 상태에서도 해석을 시도해 컨텍스트가 깨진다.
 */
@Configuration
@ConditionalOnProperty(prefix = "gcs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GcsConfig {

    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 3_000;
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 자격증명을 명시하지 않는다. 실행 환경이 알아서 찾으므로(ADC) 로컬과 배포가 같은 코드로
     * 동작한다. 로컬은 개발자 계정이 Service Account를 대행하고, 배포는 인스턴스에 부여한
     * Service Account를 쓴다.
     *
     * <p><b>여기 설정한 타임아웃·재시도가 적용되는 범위는 Storage API 호출({@code exists},
     * {@code delete})뿐이다.</b> URL 서명은 이 transport 를 타지 않는다. {@code signUrl} 은
     * credentials 를 {@code ServiceAccountSigner} 로 캐스팅해 서명을 위임하고, 키 파일이 없는
     * 우리 구성에서는 google-auth-library 가 자기 transport 로 IAM 을 호출한다(기본 20초).
     *
     * <p>그럼에도 이 설정이 필요한 이유(잠금을 쥔 채 일어나는 유일한 호출이 {@code exists})는
     * {@code docs/tech-specs/upload.md} §3.4 에 있다.
     */
    @Bean
    Storage storage() {
        HttpTransportOptions transportOptions = HttpTransportOptions.newBuilder()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setReadTimeout(READ_TIMEOUT_MILLIS)
                .build();

        return StorageOptions.newBuilder()
                .setTransportOptions(transportOptions)
                .setRetrySettings(ServiceOptions.getDefaultRetrySettings().toBuilder()
                        .setMaxAttempts(MAX_ATTEMPTS)
                        // 이 값이 없으면 기본 50초가 남아 재시도·backoff 를 합친 실제 상한이
                        // 설계값의 두 배가 된다.
                        .setTotalTimeoutDuration(TOTAL_TIMEOUT)
                        .build())
                .build()
                .getService();
    }

    @Bean
    ObjectStorage objectStorage(
            Storage storage,
            @Value("${gcs.bucket}") String bucket,
            @Value("${gcs.upload-url-expiration-minutes}") long uploadUrlExpirationMinutes,
            @Value("${gcs.view-url-expiration-minutes}") long viewUrlExpirationMinutes) {

        return new GcsObjectStorageAdapter(
                storage,
                bucket,
                Duration.ofMinutes(uploadUrlExpirationMinutes),
                Duration.ofMinutes(viewUrlExpirationMinutes));
    }
}
