package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.user.service.UserLifecycleGuard;
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
import com.star_pick.starpick.domain.ingestion.domain.InstagramUrl;
import com.star_pick.starpick.domain.ingestion.domain.YouTubeUrl;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.AttachOutcome;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.domain.user.service.UserRecipeStatsService;
import com.star_pick.starpick.global.exception.BusinessException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngestionJobService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final IngestionJobRepository repository;
    private final UserLifecycleGuard lifecycle;
    private final UploadService uploadService;
    private final IngestionProperties properties;
    private final UserRecipeStatsService userRecipeStatsService;

    @Transactional
    public IngestionJobCreateResponse create(Long userId, IngestionJobCreateRequest request) {
        lifecycle.lockActive(userId);
        IngestionJob urlJob = request.inputType() == IngestionInputType.URL ? urlJob(userId, request.url()) : null;
        // 저장할 수 없는 결과를 만드느라 분석 비용과 대기 시간을 쓰지 않는다. 슬롯은 저장할 때 쓴다.
        userRecipeStatsService.requireRemainingSlot(userId);
        Instant today = ZonedDateTime.now(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
        if (repository.countByUserIdAndCreatedAtGreaterThanEqual(userId, today) >= properties.dailyLimit()) {
            throw new BusinessException(INGESTION_DAILY_LIMIT_EXCEEDED);
        }
        if (urlJob != null) {
            return new IngestionJobCreateResponse(repository.save(urlJob).getId());
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

    /** 받는 링크 형식은 {@link YouTubeUrl}·{@link InstagramUrl} 이 정한다. 원본이 실제로 있는지는 Worker 가 확인한다. */
    private static IngestionJob urlJob(Long userId, String url) {
        Optional<YouTubeUrl> youTube = YouTubeUrl.parse(url);
        if (youTube.isPresent()) {
            return IngestionJob.queueYouTube(userId, youTube.get());
        }
        return InstagramUrl.parse(url)
                .map(instagram -> IngestionJob.queueInstagram(userId, instagram))
                .orElseThrow(() -> new BusinessException(INGESTION_URL_UNSUPPORTED));
    }

    /** 조회 URL 서명이 DB 커넥션을 점유하지 않도록 이 메서드는 트랜잭션을 열지 않는다. */
    public IngestionJobResponse getJob(Long userId, Long jobId) {
        IngestionJob job = repository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(INGESTION_JOB_NOT_FOUND));
        IngestionJobStatus visibleStatus = job.visibleStatus(Instant.now());
        // 소비된 Job 은 입력 종류와 관계없이 null 이다(공개 계약). 사진은 Recipe 삭제 때 원본이 지워지므로,
        // 서명만 해서 돌려주면(GCS 서명은 객체 존재를 확인하지 않는다) 앱이 깨진 이미지를 렌더한다.
        String previewImageUrl = job.getConsumedAt() != null ? null : previewImageUrl(userId, job);
        return new IngestionJobResponse(
                job.getId(), job.inputType(), visibleStatus, previewImageUrl,
                visibleStatus == IngestionJobStatus.RESULT_READY && job.getConsumedAt() == null
                        ? job.getResult() : null,
                visibleStatus == IngestionJobStatus.FAILED ? job.getFailureCode() : null);
    }

    private String previewImageUrl(Long userId, IngestionJob job) {
        return switch (job.getSourceType()) {
            case IMAGE -> uploadService.getViewUrl(userId, job.getInputImageKeys().getFirst());
            // 주소만 만든다. 서명·조회가 필요 없는 공개 이미지라 앱이 직접 불러온다.
            case YOUTUBE -> YouTubeUrl.parse(job.getInputUrl()).map(YouTubeUrl::thumbnailUrl).orElse(null);
            // Worker 가 embed 를 읽은 뒤 저장한 CDN 주소. 읽기 전에는 null 이다.
            case INSTAGRAM -> job.getPreviewImageUrl();
        };
    }
}
