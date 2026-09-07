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
 * <p>Ingestion(9단계) 관련 code 는 해당 단계에서 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum RecipeErrorCode implements ErrorCode {

    RECIPE_NOT_FOUND(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다."),

    /** 없거나, 남의 것이거나, 다른 용도로 발급됐거나, 실제로 업로드되지 않은 Cover Key. */
    RECIPE_COVER_INVALID(HttpStatus.BAD_REQUEST, "대표 이미지를 사용할 수 없습니다."),

    /** 이미 다른 곳에 연결된 Cover Key. 하나의 Key 는 평생 한 번만 연결된다. */
    RECIPE_COVER_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 대표 이미지입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
