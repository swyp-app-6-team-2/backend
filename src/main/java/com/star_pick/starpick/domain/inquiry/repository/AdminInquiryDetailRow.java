package com.star_pick.starpick.domain.inquiry.repository;

import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import java.time.Instant;
import java.util.List;

/** 관리자 상세. 작성자 정보는 users·profiles 에서 읽기만 한다. */
public record AdminInquiryDetailRow(
        Long inquiryId,
        Long userId,
        InquiryType type,
        String title,
        String content,
        List<String> attachmentKeys,
        String answer,
        Instant answeredAt,
        Instant createdAt,
        String nickname,
        String lastLoginProvider,
        boolean withdrawn) {
}
