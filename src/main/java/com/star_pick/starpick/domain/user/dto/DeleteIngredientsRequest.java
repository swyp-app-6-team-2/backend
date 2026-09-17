package com.star_pick.starpick.domain.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 마스터·커스텀 재료를 같은 API에서 선택 또는 전체 삭제한다. */
public record DeleteIngredientsRequest(
        @NotNull(message = "삭제 범위는 필수입니다.") IngredientDeleteMode mode,
        @NotNull(message = "삭제할 재료 목록은 필수입니다.") List<@Valid DeleteIngredientItem> ingredients
) { }
