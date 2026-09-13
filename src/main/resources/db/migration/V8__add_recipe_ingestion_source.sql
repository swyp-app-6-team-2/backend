-- 분석 결과로 저장한 Recipe 의 출처(이슈 #38). RecipeSource 테이블 대신 컬럼 3개로 둔다.
-- ingestion_job_id UNIQUE 가 "Job 하나로 Recipe 하나"의 마지막 방어선이다.
alter table recipe
    add column ingestion_job_id  bigint,
    add column source_url        varchar(2048),
    add column source_image_keys text[];

alter table recipe
    add constraint uk_recipe_ingestion_job_id unique (ingestion_job_id),
    add constraint fk_recipe_ingestion_job
        foreign key (ingestion_job_id) references ingestion_job (id),
    add constraint ck_recipe_source
        check ((registration_method = 'MANUAL'
                and ingestion_job_id is null
                and source_url is null
                and source_image_keys is null)
            or (registration_method = 'URL'
                and ingestion_job_id is not null
                and source_url is not null
                and source_image_keys is null)
            or (registration_method = 'IMAGE'
                and ingestion_job_id is not null
                and source_url is null
                and source_image_keys is not null
                and cardinality(source_image_keys) >= 1));
