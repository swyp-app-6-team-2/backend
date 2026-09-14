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
        Instagram instagram) {

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
}
