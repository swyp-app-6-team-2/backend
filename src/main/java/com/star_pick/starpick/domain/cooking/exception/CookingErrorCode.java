package com.star_pick.starpick.domain.cooking.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Cooking 도메인의 공개 오류 코드.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 분기가 조용히 깨진다.
 *
 * <p>Upload 는 연결 실패를 {@code AttachOutcome} 으로만 알려주고 HTTP 상태나 코드를 정하지 않는다.
 * 같은 실패가 Recipe 에서는 대표 이미지 오류이고 여기서는 완성 사진 오류라 각 도메인이 번역한다.
 *
 * <p>Recipe 존재·소유권 실패는 Recipe 가 소유한 {@code RECIPE_NOT_FOUND} 를 그대로 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum CookingErrorCode implements ErrorCode {

    /** 없거나, 남의 것이거나, 다른 용도로 발급됐거나, 실제로 업로드되지 않은 사진 Key. */
    COOK_HISTORY_PHOTO_INVALID(HttpStatus.BAD_REQUEST, "완성 사진을 사용할 수 없습니다."),

    /** 이미 다른 곳에 연결된 사진 Key. 하나의 Key 는 평생 한 번만 연결된다. */
    COOK_HISTORY_PHOTO_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 완성 사진입니다.");

    private final HttpStatus status;

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
