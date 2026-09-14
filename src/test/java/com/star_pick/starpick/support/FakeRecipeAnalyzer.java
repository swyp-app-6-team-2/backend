package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.ingestion.service.AnalysisInput;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalyzer;
import com.star_pick.starpick.domain.ingestion.service.UploadedVideo;
import com.star_pick.starpick.domain.ingestion.service.VideoFileState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeRecipeAnalyzer implements RecipeAnalyzer {

    public static final UploadedVideo UPLOADED =
            new UploadedVideo("files/fake-reel", "https://gemini.test/v1beta/files/fake-reel");

    private final Deque<Object> script = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Duration lastTimeout;
    private volatile AnalysisInput lastInput;

    private final Deque<VideoFileState> videoStates = new ArrayDeque<>();
    private final AtomicInteger uploads = new AtomicInteger();
    private final AtomicInteger deletedVideos = new AtomicInteger();
    private volatile Path lastUploadedFile;
    private volatile byte[] lastUploadedBytes;

    public synchronized void enqueue(AnalysisOutcome outcome) {
        script.addLast(outcome);
    }

    public synchronized void enqueue(RuntimeException exception) {
        script.addLast(exception);
    }

    public synchronized void enqueueAction(Runnable action, AnalysisOutcome outcome) {
        script.addLast(new Action(action, outcome));
    }

    /** 비어 있으면 ACTIVE 로 답한다. */
    public synchronized void enqueueVideoState(VideoFileState state) {
        videoStates.addLast(state);
    }

    @Override
    public synchronized AnalysisOutcome analyze(AnalysisInput input, Duration timeout) {
        calls.incrementAndGet();
        lastTimeout = timeout;
        lastInput = input;
        Object next = script.removeFirst();
        if (next instanceof RuntimeException exception) {
            throw exception;
        }
        if (next instanceof Action action) {
            action.beforeReturn().run();
            return action.outcome();
        }
        return (AnalysisOutcome) next;
    }

    @Override
    public UploadedVideo uploadVideo(Path file, long size, Duration timeout) {
        uploads.incrementAndGet();
        lastUploadedFile = file;
        try {
            lastUploadedBytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return UPLOADED;
    }

    @Override
    public synchronized VideoFileState videoState(UploadedVideo video, Duration timeout) {
        return videoStates.isEmpty() ? VideoFileState.ACTIVE : videoStates.removeFirst();
    }

    @Override
    public void deleteVideo(UploadedVideo video, Duration timeout) {
        deletedVideos.incrementAndGet();
    }

    public int calls() {
        return calls.get();
    }

    public Duration lastTimeout() {
        return lastTimeout;
    }

    public AnalysisInput lastInput() {
        return lastInput;
    }

    public int uploads() {
        return uploads.get();
    }

    public int deletedVideos() {
        return deletedVideos.get();
    }

    public Path lastUploadedFile() {
        return lastUploadedFile;
    }

    public byte[] lastUploadedBytes() {
        return lastUploadedBytes;
    }

    public synchronized void clear() {
        script.clear();
        calls.set(0);
        lastTimeout = null;
        lastInput = null;
        videoStates.clear();
        uploads.set(0);
        deletedVideos.set(0);
        lastUploadedFile = null;
        lastUploadedBytes = null;
    }

    private record Action(Runnable beforeReturn, AnalysisOutcome outcome) {
    }
}
