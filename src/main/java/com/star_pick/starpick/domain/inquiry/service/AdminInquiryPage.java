package com.star_pick.starpick.domain.inquiry.service;

import com.star_pick.starpick.domain.inquiry.repository.AdminInquiryRow;
import java.util.List;

/** 관리자 목록 한 페이지. 페이지 크기는 {@link InquiryAdminService#PAGE_SIZE} 로 고정이다. */
public record AdminInquiryPage(long totalCount, int page, List<AdminInquiryRow> inquiries) {

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        // page + 1 을 int 로 먼저 계산하면 page 가 int 최댓값일 때 음수로 넘친다.
        return ((long) page + 1) * InquiryAdminService.PAGE_SIZE < totalCount;
    }
}
