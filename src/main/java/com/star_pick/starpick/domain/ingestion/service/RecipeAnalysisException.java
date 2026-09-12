package com.star_pick.starpick.domain.ingestion.service;

import java.time.Duration;

public class RecipeAnalysisException extends RuntimeException {

    public enum Kind {
        RETRYABLE, CONTENT_BLOCKED, UNRECOVERABLE
    }

    private final Kind kind;
    private final Duration retryAfter;

    public RecipeAnalysisException(Kind kind, String message, Duration retryAfter) {
        super(message);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
