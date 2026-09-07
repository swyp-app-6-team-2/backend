package com.star_pick.starpick.domain.upload.domain;

/**
 * 업로드 이미지의 용도.
 *
 * <p>enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 요청이 조용히 깨진다.
 *
 * <p>{@code prefix} 는 저장소 객체 이름의 첫 구간이다. 끝에 슬래시를 붙이지 않는다.
 * 조합할 때 붙이므로, 여기에 붙이면 {@code recipe-covers//1/...} 이 된다.
 */
public enum UploadPurpose {

    RECIPE_COVER("recipe-covers"),
    COOK_HISTORY_PHOTO("cook-history"),
    INGESTION_INPUT("ingestion-inputs");

    private final String prefix;

    UploadPurpose(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
