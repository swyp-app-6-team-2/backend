package com.star_pick.starpick.domain.upload.controller.request;

import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 업로드 URL 발급 요청.
 *
 * <p>두 필드 모두 필수다. {@code @Pattern} 은 null 을 통과시키므로 {@code @NotBlank} 를 함께
 * 건다. 없으면 형식을 아예 보내지 않은 요청이 검증을 지나쳐 서버 오류가 된다.
 *
 * <p>{@code purpose} 를 enum 으로 받으므로 정의되지 않은 문자열은 Bean Validation 이전의
 * 역직렬화 단계에서 걸려 {@code 400 INVALID_REQUEST_FORMAT} 이 된다. 반면 키 자체가 없으면
 * 역직렬화 실패가 아니라 null 이라 {@code @NotNull} 이 잡아 {@code 400 REQUEST_VALIDATION_FAILED}
 * 가 된다. 둘 다 400 이므로 공개 계약을 만족한다.
 */
public record UploadUrlIssueRequest(

        @NotNull(message = "이미지 용도는 필수입니다.")
        UploadPurpose purpose,

        @NotBlank(message = "이미지 형식은 필수입니다.")
        @Pattern(regexp = "image/(jpeg|png|webp)", message = "지원하지 않는 이미지 형식입니다.")
        String contentType
) {
}
