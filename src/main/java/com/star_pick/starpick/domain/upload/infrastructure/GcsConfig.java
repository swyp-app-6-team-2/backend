package com.star_pick.starpick.domain.upload.infrastructure;

import com.google.cloud.ServiceOptions;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.star_pick.starpick.domain.upload.service.ObjectStorage;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
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
     * 바이너리 다운로드 전용 예산. 기본 클라이언트보다 훨씬 넉넉하다.
     *
     * <p>Ingestion 이 분석 사진을 합계 최대 14MB 까지 읽는다. 기본 5초 안에 받으려면 약
     * 22Mbit/s 가 지속돼야 하고, 못 받으면 {@code StorageException} 이 재시도 없이 분석 실패로
     * 이어진다. 이 경로는 잠금을 쥐지 않으므로(트랜잭션 밖) 길게 잡아도 DB 커넥션을 묶지 않는다.
     */
    private static final int DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000;
    private static final Duration DOWNLOAD_TOTAL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 자격증명을 명시하지 않는다. 실행 환경이 알아서 찾으므로(ADC) 로컬과 배포가 같은 코드로
     * 동작한다. 로컬은 개발자 계정이 Service Account를 대행하고, 배포는 인스턴스에 부여한
     * Service Account를 쓴다.
     *
     * <p><b>여기 설정한 타임아웃·재시도가 적용되는 범위는 메타데이터 수준 Storage API
     * 호출({@code exists}, {@code size}, {@code delete})뿐이다.</b> 바이너리 본문을 받는
     * {@code read} 는 {@link #downloadStorage} 를 쓴다 — 14MB 를 5초 안에 받을 수 없기 때문이다.
     * URL 서명도 이 transport 를 타지 않는다. {@code signUrl} 은 credentials 를
     * {@code ServiceAccountSigner} 로 캐스팅해 서명을 위임하고, 키 파일이 없는 우리 구성에서는
     * google-auth-library 가 자기 transport 로 IAM 을 호출한다(기본 20초).
     *
     * <p>그럼에도 이 설정이 필요한 이유(잠금을 쥔 채 일어나는 유일한 호출이 {@code exists})는
     * {@code docs/specs/upload.md} §3.4 에 있다.
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

    /**
     * 바이너리 다운로드 전용 클라이언트. {@code read} 만 이것을 쓴다.
     *
     * <p>클라이언트를 나눈 이유는 두 호출의 성격이 반대이기 때문이다. {@code exists} 는 DB
     * 잠금을 쥔 채 도는 메타데이터 조회라 짧아야 하고({@code upload.md} §3.4), {@code read} 는
     * 트랜잭션 밖에서 최대 14MB 를 받는 전송이라 길어야 한다. 한 클라이언트로는 둘 중 하나가
     * 반드시 잘못된 예산을 쓴다.
     *
     * <p>재시도는 기본값을 그대로 둔다. 다운로드 실패를 여기서 몇 번 다시 시도할지는
     * 호출자(Ingestion Worker)의 deadline 예산과 맞물려 있고, 이 어댑터는 그 예산을 모른다.
     */
    @Bean
    Storage downloadStorage() {
        HttpTransportOptions transportOptions = HttpTransportOptions.newBuilder()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setReadTimeout(DOWNLOAD_READ_TIMEOUT_MILLIS)
                .build();

        return StorageOptions.newBuilder()
                .setTransportOptions(transportOptions)
                .setRetrySettings(ServiceOptions.getDefaultRetrySettings().toBuilder()
                        .setMaxAttempts(MAX_ATTEMPTS)
                        .setTotalTimeoutDuration(DOWNLOAD_TOTAL_TIMEOUT)
                        .build())
                .build()
                .getService();
    }

    @Bean
    ObjectStorage objectStorage(
            // Storage 빈이 둘이다. 파라미터 이름에 의존하지 않도록 Qualifier 로 못 박는다.
            @Qualifier("storage") Storage storage,
            @Qualifier("downloadStorage") Storage downloadStorage,
            @Value("${gcs.bucket}") String bucket,
            @Value("${gcs.upload-url-expiration-minutes}") long uploadUrlExpirationMinutes,
            @Value("${gcs.view-url-expiration-minutes}") long viewUrlExpirationMinutes) {

        return new GcsObjectStorageAdapter(
                storage,
                downloadStorage,
                bucket,
                Duration.ofMinutes(uploadUrlExpirationMinutes),
                Duration.ofMinutes(viewUrlExpirationMinutes));
    }
}
