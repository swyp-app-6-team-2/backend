package com.star_pick.starpick.domain.recipe.controller.request;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Recipe 부분 수정 요청. 필드마다 세 가지 의미를 구분한다.
 *
 * <ul>
 *   <li>{@code null} — 요청에 없음. 기존 값을 유지한다.
 *   <li>{@code Optional.empty()} — 명시적 {@code null} 전달. 값을 제거한다.
 *   <li>{@code Optional.of(v)} — 값 전달.
 * </ul>
 *
 * <p><b>record 로 바꾸지 말 것.</b> record 는 property-based creator 라 요청에 없는 프로퍼티도
 * {@code getAbsentValue()} → {@code getNullValue()} 를 타서 {@code Optional.empty()} 가 된다.
 * 그러면 "미전달"과 "명시적 null" 이 같은 값이 되어 계약이 조용히 깨진다.
 * 이 저장소의 다른 Request DTO 가 전부 record 라 일관성 명목의 리팩터링이 일어나기 쉬우므로
 * {@code RecipeUpdateRequestDeserializationTest} 가 이 계약을 고정한다.
 *
 * <p>Bean Validation 은 타입 인자에 붙인다. Hibernate Validator 의 {@code OptionalValueExtractor}
 * 가 {@code Optional.empty()} 를 {@code null} 로 풀어 검증하므로, 필수 필드에 명시적 null 을
 * 보내면 그대로 위반이 된다.
 *
 * <p>모든 필드에 {@code requiredMode = NOT_REQUIRED} 를 명시한다. springdoc 이 {@code Optional}
 * 타입 인자에 붙은 {@code @NotNull}/{@code @NotBlank} 를 스키마의 required 로 승격시키는데,
 * PATCH 에서 그 제약의 의미는 "보내려면 null 이면 안 된다" 이지 "반드시 보내라" 가 아니다.
 */
@Getter
@NoArgsConstructor
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class RecipeUpdateRequest {

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<@NotBlank(message = "제목은 비울 수 없습니다.")
            @Size(max = 255, message = "제목은 255자를 넘을 수 없습니다.") String> title;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<@NotNull(message = "카테고리는 비울 수 없습니다.") RecipeCategory> categoryCode;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<@Min(value = 1, message = "조리 시간은 1분 이상이어야 합니다.") Integer> cookTimeMinutes;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<@NotNull(message = "인분 수는 비울 수 없습니다.")
            @Min(value = 1, message = "인분 수는 1 이상이어야 합니다.") Integer> servings;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<String> memo;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<String> coverImageKey;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<@NotNull(message = "재료 목록은 null 일 수 없습니다.")
            List<@Valid @NotNull RecipeIngredientRequest>> ingredients;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Optional<@NotNull(message = "조리 순서는 null 일 수 없습니다.")
            List<@Valid @NotNull RecipeStepRequest>> steps;

    /** 어떤 필드도 전달되지 않았는지. 빈 Request Body 는 400 REQUEST_VALIDATION_FAILED 다(02-1 §6). */
    public boolean hasNoChanges() {
        return title == null
                && categoryCode == null
                && cookTimeMinutes == null
                && servings == null
                && memo == null
                && coverImageKey == null
                && ingredients == null
                && steps == null;
    }
}
