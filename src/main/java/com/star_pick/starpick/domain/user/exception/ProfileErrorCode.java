package com.star_pick.starpick.domain.user.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProfileErrorCode implements ErrorCode {
    PROFILE_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "프로필 이미지를 사용할 수 없습니다.");
    private final HttpStatus status;
    private final String message;
    public String getCode() { return name(); }
}
