package com.star_pick.starpick.domain.inquiry.controller.response;

import com.star_pick.starpick.domain.inquiry.domain.Inquiry;
import com.star_pick.starpick.domain.inquiry.domain.InquiryStatus;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import java.time.Instant;
import java.util.List;

/**
 * 내 문의 상세.
 *
 * <p>답변 전 {@code answer}·{@code answeredAt} 은 null 이다. 값이 없어도 키는 존재해야 한다는 것이 응답 계약이라
 * {@code @JsonInclude(NON_NULL)} 을 붙이지 않는다.
 */
public record InquiryDetailResponse(
        Long inquiryId,
        InquiryType type,
        String title,
        String content,
        List<String> attachmentImageUrls,
        InquiryStatus status,
        Instant createdAt,
        String answer,
        Instant answeredAt) {

    public static InquiryDetailResponse from(Inquiry inquiry, List<String> attachmentImageUrls) {
        return new InquiryDetailResponse(inquiry.getId(), inquiry.getType(), inquiry.getTitle(),
                inquiry.getContent(), attachmentImageUrls, inquiry.status(), inquiry.getCreatedAt(),
                inquiry.getAnswer(), inquiry.getAnsweredAt());
    }
}
