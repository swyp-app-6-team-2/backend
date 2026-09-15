package com.star_pick.starpick.domain.user.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserRecipeSlotErrorCode implements ErrorCode {
    RECIPE_SLOT_EXCEEDED(HttpStatus.CONFLICT, "저장할 수 있는 레시피 슬롯이 없습니다.");

    private final HttpStatus status;
    private final String message;
    @Override public String getCode() { return name(); }
}
