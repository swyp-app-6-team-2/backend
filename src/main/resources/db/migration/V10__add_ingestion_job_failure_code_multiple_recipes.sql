-- 원본 하나에 레시피가 여러 개일 때 "레시피가 아님"과 구분해 앱이 안내할 수 있게 한다.
alter table ingestion_job drop constraint ck_ingestion_job_failure_code;
alter table ingestion_job add constraint ck_ingestion_job_failure_code
    check (failure_code in ('SOURCE_UNAVAILABLE', 'CONTENT_NOT_RECOGNIZED', 'MULTIPLE_RECIPES', 'PROCESSING_FAILED'));
