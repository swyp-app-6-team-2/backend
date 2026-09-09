package com.star_pick.starpick.domain.recipe.domain;

/**
 * 목록 조회의 정렬 기준. 상수명이 그대로 공개 API 의 {@code sort} 값이다.
 *
 * <p>Spring Data 의 {@code Sort} 로 옮기는 일은 Service 가 한다. 정렬 기준 자체는 도메인 개념이지만
 * 그것을 어떤 영속성 API 로 표현하는지는 도메인이 알 일이 아니다(CLAUDE.md §4).
 */
public enum RecipeListSort {
    LATEST,
    OLDEST
}
