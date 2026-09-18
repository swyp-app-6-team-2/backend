package com.star_pick.starpick.domain.ingestion.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion")
public record IngestionProperties(
        int dailyLimit,
        Worker worker,
        Job job,
        Retry retry,
        Image image,
        External external,
        Gemini gemini,
        Instagram instagram,
        YouTube youtube,
        Apify apify) {

    public record Worker(int concurrency, Duration pollInterval) {
    }

    public record Job(Duration deadline, Duration staleThreshold, Duration queueWaitLimit,
                      Duration resultTtl, Duration retention) {
    }

    public record Retry(int maxAttempts, List<Duration> backoffs) {
    }

    public record Image(long maxTotalBytes) {
    }

    public record External(boolean enabled) {
    }

    public record Gemini(String apiKey, String model, String baseUrl, Duration analyzeTimeout, double videoFps) {
    }

    /** 단계별 상한. 실제 timeout 은 {@code min(상한, deadline 까지 남은 시간)}. ACTIVE 대기는 deadline 까지다. */
    public record Instagram(Duration fetchTimeout, Duration mediaTimeout, long maxVideoBytes, Duration uploadTimeout) {
    }

    /** 영상 설명란 조회. {@code apiKey} 는 Worker 를 맡는 프로세스에 필수다({@code IngestionWorkerConfig}). */
    public record YouTube(String apiKey, Duration fetchTimeout) {
    }

    /**
     * 보조 수집기. {@code token} 이 비면 Bean 을 만들지 않아 경로가 꺼진다.
     * {@code minRemaining} 은 호출 뒤 남겨 둬야 하는 시간(다운로드·업로드·분석 몫)이다.
     */
    public record Apify(String token, String baseUrl, String actorId, Duration timeout, Duration minRemaining) {
    }
}
