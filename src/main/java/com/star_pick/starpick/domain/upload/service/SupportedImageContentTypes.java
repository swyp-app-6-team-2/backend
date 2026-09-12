package com.star_pick.starpick.domain.upload.service;

/**
 * 업로드가 받는 이미지 형식.
 *
 * <p>발급 요청의 Bean Validation 과, 올라온 객체를 다시 확인하는 소비 도메인이 <b>같은 목록</b>을
 * 보게 하려고 한 곳에 둔다. 양쪽에 각자 적으면 형식을 하나 추가할 때 한쪽만 고치는 실수가 난다.
 *
 * <p>{@code @Pattern} 의 {@code regexp} 는 컴파일 상수여야 해서 정규식 문자열로 노출한다.
 */
public final class SupportedImageContentTypes {

    public static final String PATTERN = "image/(jpeg|png|webp)";

    public static boolean matches(String contentType) {
        return contentType != null && contentType.matches(PATTERN);
    }

    private SupportedImageContentTypes() {
    }
}
