package com.star_pick.starpick.domain.recipe.controller.request;

import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Recipe 생성 요청.
 *
 * <p>{@code registrationMethod} 와 내부 식별자·표시 순서는 요청으로 받지 않는다. 서버가 결정한다.
 *
 * <p>{@code ingestionJobId} 는 Ingestion 단계에서 추가한다. 이번 범위는 MANUAL 생성만 처리한다.
 *
 * <p>{@code coverImageKey} 는 업로드 URL 발급 API 가 내준 값이다. 형식 검증을 걸지 않는 이유는
 * 유효성이 문자열 모양이 아니라 시스템 상태(발급 이력·소유자·용도·업로드 완료·미연결)에 있어서다.
 * 그 판단은 Upload 가 하고 실패는 {@code RECIPE_COVER_INVALID} 로 나간다.
 */
public record RecipeCreateRequest(
        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 255, message = "제목은 255자를 넘을 수 없습니다.") String title,
        @NotNull(message = "카테고리는 필수입니다.") RecipeCategory categoryCode,
        @Min(value = 1, message = "조리 시간은 1분 이상이어야 합니다.") Integer cookTimeMinutes,
        @Min(value = 1, message = "인분 수는 1 이상이어야 합니다.") Integer servings,
        String memo,
        String coverImageKey,
        List<@Valid @NotNull RecipeIngredientRequest> ingredients,
        List<@Valid @NotNull RecipeStepRequest> steps
) {
}
