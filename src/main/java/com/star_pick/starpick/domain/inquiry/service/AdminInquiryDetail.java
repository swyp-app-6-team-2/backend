package com.star_pick.starpick.domain.inquiry.service;

import com.star_pick.starpick.domain.inquiry.repository.AdminInquiryDetailRow;
import java.util.List;

public record AdminInquiryDetail(AdminInquiryDetailRow row, List<String> attachmentImageUrls) {
}
