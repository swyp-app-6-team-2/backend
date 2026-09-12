package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.ingestion.service.AnalysisInput;
import com.star_pick.starpick.domain.ingestion.service.AnalysisOutcome;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalyzer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeRecipeAnalyzer implements RecipeAnalyzer {

    private final Deque<Object> script = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Duration lastTimeout;
    private volatile AnalysisInput lastInput;

    public synchronized void enqueue(AnalysisOutcome outcome) {
        script.addLast(outcome);
    }

    public synchronized void enqueue(RuntimeException exception) {
        script.addLast(exception);
    }

    public synchronized void enqueueAction(Runnable action, AnalysisOutcome outcome) {
        script.addLast(new Action(action, outcome));
    }

    @Override
    public synchronized AnalysisOutcome analyze(AnalysisInput input, Duration timeout) {
        calls.incrementAndGet();
        lastInput = input;
        lastTimeout = timeout;
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

    public int calls() {
        return calls.get();
    }

    public Duration lastTimeout() {
        return lastTimeout;
    }

    public AnalysisInput lastInput() {
        return lastInput;
    }

    public synchronized void clear() {
        script.clear();
        calls.set(0);
        lastTimeout = null;
        lastInput = null;
    }

    private record Action(Runnable beforeReturn, AnalysisOutcome outcome) {
    }
}
