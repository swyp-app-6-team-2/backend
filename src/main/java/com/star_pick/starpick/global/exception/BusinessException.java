package com.star_pick.starpick.global.exception;

import lombok.Getter;

/**
 * 예상 가능한 비즈니스/API 실패.
 *
 * <p>오류마다 얇은 예외 클래스를 만들지 않는다(04-3). 예외 타입에 따라 retry·복구 같은
 * 실제 행동이 달라지는 경우에만 별도 타입을 둔다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
