package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.ingestion.service.InlineImage;
import com.star_pick.starpick.domain.ingestion.service.InstagramClient;
import com.star_pick.starpick.domain.ingestion.service.InstagramFetchException;
import com.star_pick.starpick.domain.ingestion.service.InstagramPost;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeInstagramClient implements InstagramClient {

    private final Deque<Object> posts = new ArrayDeque<>();
    private final Map<String, Object> media = new ConcurrentHashMap<>();
    private final List<Long> imageLimits = new CopyOnWriteArrayList<>();
    private final List<String> imageDownloads = new CopyOnWriteArrayList<>();
    private final AtomicInteger fetchCalls = new AtomicInteger();
    private volatile String lastFetch;
    private volatile Path lastVideoTarget;

    public synchronized void enqueuePost(InstagramPost post) {
        posts.addLast(post);
    }

    public synchronized void enqueuePostFailure(RuntimeException failure) {
        posts.addLast(failure);
    }

    public void putMedia(String url, byte[] content) {
        media.put(url, content);
    }

    public void failMedia(String url, RuntimeException failure) {
        media.put(url, failure);
    }

    @Override
    public synchronized InstagramPost fetchPost(String shortcode, boolean reel, Duration timeout) {
        fetchCalls.incrementAndGet();
        lastFetch = shortcode + (reel ? " reel" : " post");
        Object next = posts.removeFirst();
        if (next instanceof RuntimeException failure) {
            throw failure;
        }
        return (InstagramPost) next;
    }

    @Override
    public InlineImage downloadImage(String mediaUrl, long maxBytes, Duration timeout) {
        imageLimits.add(maxBytes);
        imageDownloads.add(mediaUrl);
        return new InlineImage("image/jpeg", content(mediaUrl));
    }

    @Override
    public long downloadVideo(String mediaUrl, Path target, long maxBytes, Duration timeout) {
        lastVideoTarget = target;
        byte[] content = content(mediaUrl);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return content.length;
    }

    public int fetchCalls() {
        return fetchCalls.get();
    }

    public String lastFetch() {
        return lastFetch;
    }

    public List<Long> imageLimits() {
        return List.copyOf(imageLimits);
    }

    public Path lastVideoTarget() {
        return lastVideoTarget;
    }

    /** {@code downloadImage} 가 받은 주소. 실패한 호출도 순서대로 남는다. */
    public List<String> imageDownloads() {
        return List.copyOf(imageDownloads);
    }

    public synchronized void clear() {
        posts.clear();
        media.clear();
        imageLimits.clear();
        imageDownloads.clear();
        fetchCalls.set(0);
        lastFetch = null;
        lastVideoTarget = null;
    }

    private byte[] content(String url) {
        Object value = media.get(url);
        if (value instanceof RuntimeException failure) {
            throw failure;
        }
        if (value == null) {
            throw new InstagramFetchException(InstagramFetchException.Kind.UNAVAILABLE, "등록되지 않은 미디어");
        }
        return (byte[]) value;
    }
}
