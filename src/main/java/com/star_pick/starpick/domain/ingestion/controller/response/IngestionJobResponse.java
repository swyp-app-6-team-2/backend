package com.star_pick.starpick.domain.ingestion.controller.response;

import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionInputType;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;

public record IngestionJobResponse(
        Long ingestionJobId,
        IngestionInputType inputType,
        IngestionJobStatus status,
        String previewImageUrl,
        RecipeDraft result,
        IngestionFailureCode failureCode) {
}
