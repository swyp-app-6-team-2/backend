package com.star_pick.starpick.domain.recipe.domain;

/**
 * Recipe 카테고리. 7종 고정이며 최종 Recipe 에서 null 을 허용하지 않는다.
 *
 * <p>상수명이 그대로 공개 API 의 {@code categoryCode} 값이다. rename 하면 FE 가 깨진다.
 */
public enum RecipeCategory {
    KOREAN,
    WESTERN,
    CHINESE,
    JAPANESE,
    BUNSIK,
    ASIAN,
    OTHER
}
