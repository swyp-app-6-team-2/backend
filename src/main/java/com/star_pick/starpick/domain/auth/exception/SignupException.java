package com.star_pick.starpick.domain.auth.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class SignupException extends RuntimeException {
    private final HttpStatus status;

    private SignupException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static SignupException termsRequired() {
        return new SignupException(HttpStatus.BAD_REQUEST, "필수 약관에 동의해야 합니다.");
    }

    public static SignupException invalidToken() {
        return new SignupException(HttpStatus.UNAUTHORIZED,
                "회원가입 인증 정보가 유효하지 않습니다. 다시 로그인해주세요.");
    }

    public static SignupException alreadyRegistered() {
        return new SignupException(HttpStatus.CONFLICT, "이미 가입된 계정입니다. 다시 로그인해주세요.");
    }
}
