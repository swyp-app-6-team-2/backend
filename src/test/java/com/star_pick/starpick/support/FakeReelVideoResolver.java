package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.ingestion.service.ReelVideo;
import com.star_pick.starpick.domain.ingestion.service.ReelVideoResolver;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeReelVideoResolver implements ReelVideoResolver {

    private volatile ReelVideo next;
    private volatile RuntimeException failure;
    private volatile String lastUrl;
    private final AtomicInteger calls = new AtomicInteger();

    public void enqueue(ReelVideo video) {
        next = video;
    }

    public void fail(RuntimeException exception) {
        failure = exception;
    }

    public int calls() {
        return calls.get();
    }

    public String lastUrl() {
        return lastUrl;
    }

    public void clear() {
        next = null;
        failure = null;
        lastUrl = null;
        calls.set(0);
    }

    @Override
    public Optional<ReelVideo> resolve(String url, Duration timeout) {
        calls.incrementAndGet();
        lastUrl = url;
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(next);
    }
}
