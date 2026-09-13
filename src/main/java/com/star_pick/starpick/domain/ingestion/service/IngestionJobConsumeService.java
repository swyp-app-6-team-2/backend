package com.star_pick.starpick.domain.ingestion.service;

import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_JOB_ALREADY_CONSUMED;
import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_JOB_EXPIRED;
import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_JOB_INVALID_STATE;
import static com.star_pick.starpick.domain.ingestion.exception.IngestionErrorCode.INGESTION_JOB_NOT_FOUND;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recipe 가 분석 결과를 저장할 때 쓰는 경계. Recipe 는 Ingestion 의 Repository·Entity 를 직접 보지 않는다.
 *
 * <p>잠금과 소비를 나눈 이유: 이 Job 으로 만든 Recipe 가 이미 있는지는 Recipe 만 알 수 있는데,
 * 그 확인이 잠금 뒤·상태 검사 앞에 와야 한다. 응답을 못 받고 다시 누른 정상 요청이
 * "이미 소비됨"으로 거절되지 않게 하기 위해서다.
 *
 * <p>{@code MANDATORY} 인 이유: 트랜잭션 밖에서 부르면 잠금이 메서드가 끝나는 즉시 풀려
 * 직렬화가 조용히 깨진다. 그런 호출은 예외로 드러나게 한다.
 */
@Service
@RequiredArgsConstructor
public class IngestionJobConsumeService {

    private final IngestionJobRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public IngestionJobOrigin lockOwnedJob(Long userId, Long ingestionJobId) {
        IngestionJob job = lock(userId, ingestionJobId);
        return new IngestionJobOrigin(job.getInputUrl(), job.getInputImageKeys());
    }

    /** {@link #lockOwnedJob} 뒤 같은 트랜잭션에서 부른다. 검사 순서가 공개 계약의 판정 순서다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Long userId, Long ingestionJobId) {
        IngestionJob job = lock(userId, ingestionJobId);
        Instant now = Instant.now();
        if (job.getConsumedAt() != null) {
            throw new BusinessException(INGESTION_JOB_ALREADY_CONSUMED);
        }
        IngestionJobStatus visibleStatus = job.visibleStatus(now);
        if (visibleStatus == IngestionJobStatus.EXPIRED) {
            throw new BusinessException(INGESTION_JOB_EXPIRED);
        }
        if (visibleStatus != IngestionJobStatus.RESULT_READY) {
            throw new BusinessException(INGESTION_JOB_INVALID_STATE);
        }
        job.consume(now);
    }

    private IngestionJob lock(Long userId, Long ingestionJobId) {
        return repository.findByIdAndUserIdForUpdate(ingestionJobId, userId)
                .orElseThrow(() -> new BusinessException(INGESTION_JOB_NOT_FOUND));
    }
}
