package com.star_pick.starpick.domain.inquiry.service;

import com.star_pick.starpick.domain.inquiry.domain.InquiryStatus;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import com.star_pick.starpick.domain.inquiry.exception.InquiryErrorCode;
import com.star_pick.starpick.domain.inquiry.repository.AdminInquiryDetailRow;
import com.star_pick.starpick.domain.inquiry.repository.InquiryAdminQuery;
import com.star_pick.starpick.domain.inquiry.repository.InquiryRepository;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 문의 유스케이스. 1년 조회 제한이 없다.
 *
 * <p>목록은 개수·페이지 조회를 한 읽기 트랜잭션에서 한다. 상세는 {@code InquiryAdminQuery#findDetail} 의 읽기
 * 트랜잭션이 끝난 뒤 조회 URL 서명을 하므로 이 메서드에는 트랜잭션을 걸지 않는다. 답변 저장은 Repository 의
 * UPDATE 한 문장이다.
 */
@Service
@RequiredArgsConstructor
public class InquiryAdminService {

    public static final int PAGE_SIZE = 20;

    private final InquiryAdminQuery inquiryAdminQuery;

    private final InquiryRepository inquiryRepository;

    private final UploadService uploadService;

    @Transactional(readOnly = true)
    public AdminInquiryPage getInquiries(InquiryStatus status, InquiryType type, int page) {
        return new AdminInquiryPage(
                inquiryAdminQuery.count(status, type),
                page,
                inquiryAdminQuery.findPage(status, type, PAGE_SIZE, (long) page * PAGE_SIZE));
    }

    public AdminInquiryDetail getInquiry(Long inquiryId) {
        AdminInquiryDetailRow row = inquiryAdminQuery.findDetail(inquiryId)
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND));
        // 사진은 작성자에게 발급된 Key 라 작성자 기준으로 서명해야 소유자 확인을 통과한다.
        List<String> urls = row.attachmentKeys().stream()
                .map(key -> uploadService.getViewUrl(row.userId(), key))
                .filter(Objects::nonNull)
                .toList();
        return new AdminInquiryDetail(row, urls);
    }

    /** 답변을 교체한다. 첫 답변 시각은 유지한다. */
    public void saveAnswer(Long inquiryId, String answer) {
        if (inquiryRepository.saveAnswer(inquiryId, answer, Instant.now()) == 0) {
            throw new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND);
        }
    }
}
