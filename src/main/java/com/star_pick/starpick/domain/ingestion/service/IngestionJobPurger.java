package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.UploadService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngestionJobPurger {

    private final IngestionJobRepository repository;
    private final UploadService uploadService;

    /**
     * Job 하나를 트랜잭션 하나로 지우고 입력 사진을 해제한다.
     *
     * <p>이 메서드는 유지보수 스케줄러와 별도 Bean에 있어야 호출이 Spring 프록시를 거치고 건별
     * 트랜잭션이 적용된다. 여러 Job을 한 트랜잭션에 묶으면 업로드 파일의 커밋 후 삭제가 한꺼번에
     * 실행되어 저장소가 느릴 때 DB 커넥션을 오래 점유한다.
     */
    @Transactional
    public void purgeOne(Long jobId) {
        IngestionJob job = repository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        List<String> keys = job.getInputImageKeys();
        if (!keys.isEmpty()) {
            uploadService.releaseAndDeleteFiles(
                    job.getUserId(), keys, UploadPurpose.INGESTION_INPUT);
        }
        repository.delete(job);
    }
}
