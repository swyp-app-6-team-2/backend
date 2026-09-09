package com.star_pick.starpick.domain.recipe.controller.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * PATCH 의 "미전달 vs 명시적 null" 구분을 고정하는 테스트.
 *
 * <p>이 테스트가 깨진다면 {@link RecipeUpdateRequest} 가 record 로 바뀌었거나 Jackson 의
 * absent 값 처리 설정이 달라진 것이다. 둘 다 "전달하지 않은 필드는 유지한다"는 API 계약을
 * 조용히 깨뜨리므로, 구현을 되돌리지 말고 원인을 먼저 확인해야 한다.
 *
 * <p>Spring Context 를 띄우지 않는다. Boot 4 의 JSON 스택인 Jackson 3 를 직접 쓴다.
 */
class RecipeUpdateRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private RecipeUpdateRequest read(String json) {
        return jsonMapper.readValue(json, RecipeUpdateRequest.class);
    }

    @Test
    @DisplayName("빈 객체는 모든 필드가 null 이다 — 전부 미전달")
    void emptyObjectLeavesEveryFieldNull() {
        RecipeUpdateRequest request = read("{}");

        assertThat(request.getTitle()).isNull();
        assertThat(request.getCategoryCode()).isNull();
        assertThat(request.getCookTimeMinutes()).isNull();
        assertThat(request.getServings()).isNull();
        assertThat(request.getMemo()).isNull();
        assertThat(request.getIngredients()).isNull();
        assertThat(request.getSteps()).isNull();
        assertThat(request.hasNoChanges()).isTrue();
    }

    @Test
    @DisplayName("명시적 null 은 Optional.empty() 이고 나머지 필드는 여전히 null 이다")
    void explicitNullIsDistinguishedFromAbsent() {
        RecipeUpdateRequest request = read("{\"memo\": null}");

        assertThat(request.getMemo()).isEqualTo(Optional.empty());
        assertThat(request.getTitle()).isNull();
        assertThat(request.getIngredients()).isNull();
        assertThat(request.hasNoChanges()).isFalse();
    }

    @Test
    @DisplayName("값을 전달하면 Optional.of 다")
    void valueIsWrapped() {
        RecipeUpdateRequest request = read("{\"memo\": \"조금 맵게\", \"title\": \"김치찌개\"}");

        assertThat(request.getMemo()).contains("조금 맵게");
        assertThat(request.getTitle()).contains("김치찌개");
    }

    @Test
    @DisplayName("enum 과 숫자도 같은 규칙을 따른다")
    void enumAndNumberFollowTheSameRule() {
        RecipeUpdateRequest request = read("{\"categoryCode\": \"KOREAN\", \"servings\": 2, \"cookTimeMinutes\": null}");

        assertThat(request.getCategoryCode()).contains(RecipeCategory.KOREAN);
        assertThat(request.getServings()).contains(2);
        assertThat(request.getCookTimeMinutes()).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("빈 배열은 Optional.of(빈 리스트) — 전체 삭제 의미")
    void emptyArrayMeansDeleteAll() {
        RecipeUpdateRequest request = read("{\"ingredients\": []}");

        assertThat(request.getIngredients()).isPresent();
        assertThat(request.getIngredients().orElseThrow()).isEmpty();
    }

    @Test
    @DisplayName("배열에 명시적 null 을 보내면 Optional.empty() — 검증에서 걸러진다")
    void explicitNullArrayIsEmptyOptional() {
        RecipeUpdateRequest request = read("{\"ingredients\": null}");

        assertThat(request.getIngredients()).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("배열 요소가 있으면 순서대로 담긴다")
    void arrayElementsArePreserved() {
        RecipeUpdateRequest request = read("""
                {"ingredients": [
                  {"ingredientId":1,"name":"김치","amountText":"1/4포기"},
                  {"name":"두부"}
                ]}
                """);

        assertThat(request.getIngredients().orElseThrow())
                .containsExactly(
                        new RecipeIngredientRequest(1L, "김치", "1/4포기"),
                        new RecipeIngredientRequest(null, "두부", null));
    }
}
