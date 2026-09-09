package com.star_pick.starpick.domain.recipe.controller.response;

import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import java.util.List;

/**
 * Recipe 목록 조회 응답.
 *
 * <p>{@code totalCount} 는 페이지 크기가 아니라 현재 조건의 전체 결과 수다. 지금은 조건이 없어
 * 사용자의 총 Recipe 수와 같고, 검색·필터가 붙으면 자연히 "조건에 맞는 개수"가 된다.
 *
 * <p>카드가 쓰는 필드만 담는다. {@code memo}, {@code steps}, {@code source},
 * {@code cookTimeMinutes}, {@code servings} 는 상세 조회 전용이다.
 */
public record RecipeListResponse(
        long totalCount,
        List<RecipeSummary> recipes
) {

    /**
     * {@code coverImageUrl} 은 대표 이미지가 없거나 서명에 실패하면 null 이다. 값이 없어도 키는
     * null 로 존재해야 한다는 것이 응답 계약이라(02-0) {@code @JsonInclude(NON_NULL)} 을 붙이지 않는다.
     */
    public record RecipeSummary(
            Long recipeId,
            String title,
            RecipeCategory categoryCode,
            String coverImageUrl,
            List<String> ingredientNames
    ) {
    }
}
