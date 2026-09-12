package com.star_pick.starpick.domain.ingestion.service;

import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_DAILY_LIMIT_EXCEEDED;
import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_INPUT_IMAGE_ALREADY_USED;
import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_INPUT_IMAGE_INVALID;
import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_JOB_NOT_FOUND;
import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_URL_UNSUPPORTED;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.controller.request.IngestionJobCreateRequest;
import com.star_pick.starpick.domain.ingestion.controller.response.IngestionJobCreateResponse;
import com.star_pick.starpick.domain.ingestion.controller.response.IngestionJobResponse;
import com.star_pick.starpick.domain.ingestion.domain.IngestionInputType;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.AttachOutcome;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.exception.BusinessException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngestionJobService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final IngestionJobRepository repository;
    private final UploadService uploadService;
    private final IngestionProperties properties;

    @Transactional
    public IngestionJobCreateResponse create(Long userId, IngestionJobCreateRequest request) {
        if (request.inputType() == IngestionInputType.URL) {
            throw new BusinessException(INGESTION_URL_UNSUPPORTED);
        }
        Instant today = ZonedDateTime.now(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
        if (repository.countByUserIdAndCreatedAtGreaterThanEqual(userId, today) >= properties.dailyLimit()) {
            throw new BusinessException(INGESTION_DAILY_LIMIT_EXCEEDED);
        }
        for (String key : request.inputImageKeys()) {
            switch (uploadService.attach(userId, key, UploadPurpose.INGESTION_INPUT)) {
                case INVALID -> throw new BusinessException(INGESTION_INPUT_IMAGE_INVALID);
                case ALREADY_ATTACHED -> throw new BusinessException(INGESTION_INPUT_IMAGE_ALREADY_USED);
                case ATTACHED -> { }
            }
        }
        IngestionJob job = repository.save(IngestionJob.queueImage(userId, request.inputImageKeys()));
        return new IngestionJobCreateResponse(job.getId());
    }

    /** 조회 URL 서명이 DB 커넥션을 점유하지 않도록 이 메서드는 트랜잭션을 열지 않는다. */
    public IngestionJobResponse getJob(Long userId, Long jobId) {
        IngestionJob job = repository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(INGESTION_JOB_NOT_FOUND));
        IngestionJobStatus visibleStatus = job.visibleStatus(Instant.now());
        // 소비된 Job 은 null 이다. Recipe 가 삭제되면 원본 사진도 함께 지워지므로, 서명만 해서
        // 돌려주면(GCS 서명은 객체 존재를 확인하지 않는다) 앱이 깨진 이미지를 렌더한다.
        String previewImageUrl = job.getConsumedAt() != null || job.getInputImageKeys().isEmpty()
                ? null
                : uploadService.getViewUrl(userId, job.getInputImageKeys().getFirst());
        return new IngestionJobResponse(
                job.getId(), job.inputType(), visibleStatus, previewImageUrl,
                visibleStatus == IngestionJobStatus.RESULT_READY && job.getConsumedAt() == null
                        ? job.getResult() : null,
                visibleStatus == IngestionJobStatus.FAILED ? job.getFailureCode() : null);
    }
}
