package com.star_pick.starpick.domain.auth.exception;

public class InvalidSocialTokenException extends RuntimeException{
    public InvalidSocialTokenException() {
        super("소셜 인증에 실패했습니다.");
    }
}
