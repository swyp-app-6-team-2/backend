package com.star_pick.starpick.domain.ingestion.exception;

import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;

public class IngestionInputException extends RuntimeException {

    private final IngestionFailureCode failureCode;

    public IngestionInputException(String message) {
        this(IngestionFailureCode.PROCESSING_FAILED, message);
    }

    public IngestionInputException(IngestionFailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public IngestionFailureCode failureCode() {
        return failureCode;
    }
}
