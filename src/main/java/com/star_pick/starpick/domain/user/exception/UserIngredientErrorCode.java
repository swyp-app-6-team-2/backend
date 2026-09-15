package com.star_pick.starpick.domain.user.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserIngredientErrorCode implements ErrorCode {
    USER_INGREDIENT_INVALID(HttpStatus.BAD_REQUEST, "선택한 재료가 없거나 등록할 수 없는 재료입니다.");

    private final HttpStatus status;
    private final String message;
    @Override public String getCode() { return name(); }
}
