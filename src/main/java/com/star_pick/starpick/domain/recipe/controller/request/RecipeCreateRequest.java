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
 * <p>{@code ingestionJobId} 와 {@code coverImageKey} 는 각각 Ingestion 단계와 이미지 업로드
 * 단계에서 추가한다. 이번 범위는 MANUAL 생성만 처리한다.
 */
public record RecipeCreateRequest(
        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 255, message = "제목은 255자를 넘을 수 없습니다.") String title,
        @NotNull(message = "카테고리는 필수입니다.") RecipeCategory categoryCode,
        @Min(value = 1, message = "조리 시간은 1분 이상이어야 합니다.") Integer cookTimeMinutes,
        @Min(value = 1, message = "인분 수는 1 이상이어야 합니다.") Integer servings,
        String memo,
        List<@Valid @NotNull RecipeIngredientRequest> ingredients,
        List<@Valid @NotNull RecipeStepRequest> steps
) {
}
