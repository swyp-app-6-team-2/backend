-- URL 레시피의 원본 대표 이미지(Instagram 첫 카드를 서버가 복사한 GCS 객체 Key). YouTube 는 조회 시 계산하므로 저장하지 않는다.
-- Worker 가 분석 결과와 함께 Job 에 기록하고, Recipe 로 저장될 때 Recipe 로 옮긴다.
alter table ingestion_job add column source_thumbnail_key varchar(255);

alter table recipe add column source_thumbnail_key varchar(255);
alter table recipe
    add constraint ck_recipe_source_thumbnail
        check (source_thumbnail_key is null or registration_method = 'URL');
