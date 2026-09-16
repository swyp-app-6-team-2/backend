package com.star_pick.starpick.domain.inquiry.service;

import com.star_pick.starpick.domain.user.service.UserLifecycleGuard;
import com.star_pick.starpick.domain.inquiry.controller.request.InquiryCreateRequest;
import com.star_pick.starpick.domain.inquiry.controller.response.InquiryDetailResponse;
import com.star_pick.starpick.domain.inquiry.controller.response.InquiryListResponse;
import com.star_pick.starpick.domain.inquiry.domain.Inquiry;
import com.star_pick.starpick.domain.inquiry.exception.InquiryErrorCode;
import com.star_pick.starpick.domain.inquiry.infrastructure.discord.InquiryDiscordNotifier;
import com.star_pick.starpick.domain.inquiry.repository.InquiryRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.exception.BusinessException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 사용자 문의 유스케이스. */
@Service
@RequiredArgsConstructor
public class InquiryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final InquiryRepository inquiryRepository;
    private final UserLifecycleGuard lifecycle;

    private final UploadService uploadService;

    private final InquiryDiscordNotifier discordNotifier;

    /**
     * 문의를 접수한다.
     *
     * <p>사진 연결과 저장이 한 트랜잭션이다. Key 하나라도 실패하면 앞서 연결한 Key 까지 롤백된다.
     * 연결은 저장소 존재 확인을 트랜잭션 안에서 호출한다(Recipe 대표 이미지와 같다).
     */
    @Transactional
    public Long create(Long userId, InquiryCreateRequest request) {
        lifecycle.lockActive(userId);
        List<String> keys = request.attachmentKeysOrEmpty();
        for (String key : keys) {
            switch (uploadService.attach(userId, key, UploadPurpose.INQUIRY_ATTACHMENT)) {
                case INVALID -> throw new BusinessException(InquiryErrorCode.INQUIRY_ATTACHMENT_INVALID);
                case ALREADY_ATTACHED -> throw new BusinessException(InquiryErrorCode.INQUIRY_ATTACHMENT_ALREADY_USED);
                case ATTACHED -> { }
            }
        }
        Inquiry saved = inquiryRepository.save(
                Inquiry.create(userId, request.type(), request.title(), request.content(), keys));
        notifyAfterCommit(saved);
        return saved.getId();
    }

    /**
     * 커밋 이후 운영 Discord 에 한 번 알린다. 트랜잭션 안에서만 부른다.
     *
     * <p>사진 Key 가 막혀 롤백되면 이 콜백은 돌지 않는다. 외부 호출을 트랜잭션 안에 두지 않는 이유는
     * {@code UploadService} 의 저장소 파일 삭제와 같다.
     */
    private void notifyAfterCommit(Inquiry inquiry) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                discordNotifier.notifyCreated(inquiry.getId(), inquiry.getType(), inquiry.getCreatedAt());
            }
        });
    }

    /** 최근 1년 문의를 최신순으로 한 페이지 조회한다. */
    @Transactional(readOnly = true)
    public InquiryListResponse getInquiries(Long userId, int page, int size) {
        // created_at 만으로 정렬하면 같은 시각의 행이 페이지 경계에서 중복·누락된다. id 로 순서를 고정한다.
        Page<Inquiry> found = inquiryRepository.findByUserIdAndCreatedAtGreaterThanEqual(
                userId, visibleFrom(), PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new InquiryListResponse(found.getTotalElements(),
                found.getContent().stream().map(InquiryListResponse.InquirySummary::from).toList());
    }

    /**
     * 최근 1년 내 내 문의 상세. 없거나 남의 것이거나 1년이 지났으면 모두 404 다.
     *
     * <p><b>{@code @Transactional} 을 붙이지 않는 것이 의도다.</b> 조회는 Repository 의 짧은 읽기 트랜잭션에서
     * 끝나고, 조회 URL 서명은 커넥션을 쥐지 않은 채 수행한다. Inquiry 에는 지연 로딩 컬렉션이 없다.
     */
    public InquiryDetailResponse getInquiry(Long userId, Long inquiryId) {
        Inquiry inquiry = inquiryRepository
                .findByIdAndUserIdAndCreatedAtGreaterThanEqual(inquiryId, userId, visibleFrom())
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND));
        // 서명에 실패한 사진은 뺀다. 사진 한 장 때문에 상세 전체를 실패시키지 않는다.
        List<String> urls = inquiry.attachmentKeyList().stream()
                .map(key -> uploadService.getViewUrl(userId, key))
                .filter(Objects::nonNull)
                .toList();
        return InquiryDetailResponse.from(inquiry, urls);
    }

    /** "최근 1년"은 한국 달력 기준 1년 전 같은 시각부터다. */
    private static Instant visibleFrom() {
        return ZonedDateTime.now(SEOUL).minusYears(1).toInstant();
    }
}
