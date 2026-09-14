package com.star_pick.starpick.domain.auth.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인 정보가 유효하지 않습니다. 다시 로그인해주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
