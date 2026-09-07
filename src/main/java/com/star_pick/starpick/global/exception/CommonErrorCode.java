package com.star_pick.starpick.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 도메인에 속하지 않는 공통 실패.
 *
 * <p>04-4 공통 응답 규칙이 code 문자열까지 지정한 5개만 둔다. 404(없는 경로)·405·415 는
 * FE 가 분기할 대상이 아니므로 code 를 부여하지 않는다(02-0).
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 분기가 조용히 깨진다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    REQUEST_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청값이 올바르지 않습니다."),
    INVALID_REQUEST_FORMAT(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
