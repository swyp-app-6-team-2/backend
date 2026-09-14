package com.star_pick.starpick.domain.ingestion.service;

import java.time.Duration;

public class RecipeAnalysisException extends RuntimeException {

    public enum Kind {
        RETRYABLE, CONTENT_BLOCKED, UNRECOVERABLE,
        /** Gemini 가 입력 자체를 받아들이지 않았다(400 INVALID_ARGUMENT, 키 오류 제외). 재시도하지 않는다. */
        INPUT_REJECTED
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
