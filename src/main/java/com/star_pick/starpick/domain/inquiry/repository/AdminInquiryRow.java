package com.star_pick.starpick.domain.inquiry.repository;

import com.star_pick.starpick.domain.inquiry.domain.InquiryStatus;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import java.time.Instant;

/** 관리자 목록 한 줄. 닉네임은 프로필이 없으면 null 이다. */
public record AdminInquiryRow(
        Long inquiryId,
        InquiryType type,
        String title,
        InquiryStatus status,
        Instant createdAt,
        String nickname,
        boolean withdrawn) {
}
