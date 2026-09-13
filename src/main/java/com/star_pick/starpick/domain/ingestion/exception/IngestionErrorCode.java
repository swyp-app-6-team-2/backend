package com.star_pick.starpick.domain.ingestion.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum IngestionErrorCode implements ErrorCode {

    INGESTION_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."),
    INGESTION_URL_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 링크입니다."),
    INGESTION_INPUT_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "분석할 사진을 사용할 수 없습니다."),
    INGESTION_INPUT_IMAGE_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 사진입니다."),
    INGESTION_DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 분석할 수 있는 횟수를 모두 사용했습니다."),
    INGESTION_JOB_ALREADY_CONSUMED(HttpStatus.CONFLICT, "이미 레시피로 저장한 분석 결과입니다."),
    INGESTION_JOB_EXPIRED(HttpStatus.CONFLICT, "분석 결과를 사용할 수 있는 기간이 지났습니다."),
    INGESTION_JOB_INVALID_STATE(HttpStatus.CONFLICT, "아직 레시피로 저장할 수 없는 분석 작업입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
