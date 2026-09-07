package com.star_pick.starpick.domain.recipe.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Recipe 도메인이 소유하는 실패.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 분기가 조용히 깨진다.
 *
 * <p>Cover 이미지(3단계)와 Ingestion(9단계) 관련 code 는 해당 단계에서 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum RecipeErrorCode implements ErrorCode {

    RECIPE_NOT_FOUND(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
