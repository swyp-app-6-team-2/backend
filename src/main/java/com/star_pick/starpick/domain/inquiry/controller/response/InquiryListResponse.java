package com.star_pick.starpick.domain.inquiry.controller.response;

import com.star_pick.starpick.domain.inquiry.domain.Inquiry;
import com.star_pick.starpick.domain.inquiry.domain.InquiryStatus;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import java.time.Instant;
import java.util.List;

/** 내 문의 목록. {@code totalCount} 는 페이지 크기가 아니라 조건에 맞는 전체 수다. */
public record InquiryListResponse(long totalCount, List<InquirySummary> inquiries) {

    /** 목록에는 사진과 답변을 넣지 않는다. 미리보기 길이는 앱이 정하므로 content 는 전체를 준다. */
    public record InquirySummary(
            Long inquiryId,
            InquiryType type,
            String title,
            String content,
            InquiryStatus status,
            Instant createdAt) {

        public static InquirySummary from(Inquiry inquiry) {
            return new InquirySummary(inquiry.getId(), inquiry.getType(), inquiry.getTitle(),
                    inquiry.getContent(), inquiry.status(), inquiry.getCreatedAt());
        }
    }
}
