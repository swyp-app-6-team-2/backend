package com.star_pick.starpick.domain.inquiry.controller.request;

import com.star_pick.starpick.domain.inquiry.domain.Inquiry;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

/**
 * 문의 접수 요청.
 *
 * <p>{@code type} 을 enum 으로 받으므로 없는 값은 역직렬화 단계에서 {@code 400 INVALID_REQUEST_FORMAT} 이 되고,
 * 키 자체가 없으면 {@code @NotNull} 이 {@code 400 REQUEST_VALIDATION_FAILED} 로 잡는다.
 */
public record InquiryCreateRequest(

        @NotNull(message = "문의 유형은 필수입니다.")
        InquiryType type,

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 255, message = "제목은 255자를 넘을 수 없습니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        @Size(max = 2000, message = "내용은 2000자를 넘을 수 없습니다.")
        String content,

        @Size(max = Inquiry.MAX_ATTACHMENTS, message = "사진은 5장까지 첨부할 수 있습니다.")
        List<@NotBlank(message = "사진 Key는 비어 있을 수 없습니다.") String> attachmentKeys) {

    /** 생략과 명시적 null 을 같은 빈 배열로 다룬다. */
    public List<String> attachmentKeysOrEmpty() {
        return attachmentKeys == null ? List.of() : attachmentKeys;
    }

    // is 로 시작하는 검증 메서드라 springdoc 이 요청 필드로 문서화한다. 문서에서만 숨긴다.
    // 필드 오류 이름은 attachmentKeysUnique 가 된다(IngestionJobCreateRequest 와 같은 방식).
    @Schema(hidden = true)
    @AssertTrue(message = "사진 Key가 중복되었습니다.")
    public boolean isAttachmentKeysUnique() {
        return attachmentKeys == null || new HashSet<>(attachmentKeys).size() == attachmentKeys.size();
    }
}
