package com.star_pick.starpick.domain.ingestion.service;

/** 메시지에 URL·caption·응답 본문을 넣지 않는다. */
public class InstagramFetchException extends RuntimeException {

    public enum Kind {
        /** timeout, 연결 끊김, 본문 읽기 멈춤, 5xx */
        RETRYABLE,
        /** 삭제·비공개·차단·구조 변경 등 원본을 쓸 수 없음 */
        UNAVAILABLE,
        /** 우리가 정한 크기 상한 초과 */
        TOO_LARGE
    }

    private final Kind kind;
    private final InstagramFailure failure;

    public InstagramFetchException(Kind kind, String message) {
        this(kind, InstagramFailure.UNKNOWN, message);
    }

    public InstagramFetchException(Kind kind, InstagramFailure failure, String message) {
        super(message);
        this.kind = kind;
        this.failure = failure;
    }

    public Kind kind() {
        return kind;
    }

    public InstagramFailure failure() {
        return failure;
    }
}
