package com.star_pick.starpick.domain.auth.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인 정보가 유효하지 않습니다. 다시 로그인해주세요."),
    NAVER_ACCESS_TOKEN_REQUIRED(HttpStatus.BAD_REQUEST, "네이버 계정 연결 해제를 위한 인증 정보가 필요합니다."),
    NAVER_CONNECTION_REVOKE_FAILED(HttpStatus.BAD_GATEWAY, "네이버 계정 연결을 해제하지 못했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
