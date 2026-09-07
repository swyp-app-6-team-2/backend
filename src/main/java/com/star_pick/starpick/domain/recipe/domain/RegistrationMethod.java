package com.star_pick.starpick.domain.recipe.domain;

/**
 * Recipe 등록 방식. 서버가 결정하며 요청으로 받지 않고 생성 이후 수정할 수 없다.
 *
 * <p>{@code ingestionJobId} 가 없으면 {@code MANUAL}, 있으면 IngestionJob 의 입력 방식에 따라
 * {@code URL} 또는 {@code IMAGE} 다.
 */
public enum RegistrationMethod {
    MANUAL,
    URL,
    IMAGE
}
