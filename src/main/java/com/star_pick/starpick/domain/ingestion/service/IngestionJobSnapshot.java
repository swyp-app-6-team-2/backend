package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionSourceType;
import java.time.Instant;
import java.util.List;

public record IngestionJobSnapshot(
        Long id,
        IngestionSourceType sourceType,
        List<String> inputImageKeys,
        int attempt,
        Instant startedAt) {

    static IngestionJobSnapshot from(IngestionJob job) {
        return new IngestionJobSnapshot(job.getId(), job.getSourceType(),
                job.getInputImageKeys(), job.getAttempt(), job.getStartedAt());
    }
}
