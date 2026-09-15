package com.star_pick.starpick.domain.inquiry.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Inquiry 도메인이 소유하는 실패.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 분기가 조용히 깨진다.
 */
@Getter
@RequiredArgsConstructor
public enum InquiryErrorCode implements ErrorCode {

    /** 없거나, 다른 사용자의 것이거나, 접수한 지 1년이 지난 문의. */
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다."),

    /** 없거나, 남의 것이거나, 다른 용도로 발급됐거나, 실제로 업로드되지 않은 Key. */
    INQUIRY_ATTACHMENT_INVALID(HttpStatus.BAD_REQUEST, "첨부 사진을 사용할 수 없습니다."),

    /** 이미 다른 곳에 연결된 Key. 하나의 Key 는 평생 한 번만 연결된다. */
    INQUIRY_ATTACHMENT_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 첨부 사진입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
