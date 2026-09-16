package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.ingestion.service.YouTubeMetadataClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeYouTubeMetadataClient implements YouTubeMetadataClient {

    private final Map<String, Object> descriptions = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Duration lastTimeout;

    public void put(String videoId, String description) {
        descriptions.put(videoId, description);
    }

    /** 조회가 예외로 실패하는 경우. Worker 가 이를 막고 분석을 계속하는지 확인한다. */
    public void fail(String videoId, RuntimeException failure) {
        descriptions.put(videoId, failure);
    }

    @Override
    public String description(String videoId, Duration timeout) {
        calls.incrementAndGet();
        lastTimeout = timeout;
        Object value = descriptions.get(videoId);
        if (value instanceof RuntimeException failure) {
            throw failure;
        }
        return (String) value;
    }

    public int calls() {
        return calls.get();
    }

    public Duration lastTimeout() {
        return lastTimeout;
    }

    public void clear() {
        descriptions.clear();
        calls.set(0);
        lastTimeout = null;
    }
}
