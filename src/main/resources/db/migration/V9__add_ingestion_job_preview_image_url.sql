-- Instagram 분석 중 화면의 원본 썸네일. Worker 가 embed 를 읽은 뒤 저장한다(사진·YouTube 는 조회 때 계산).
alter table ingestion_job add column preview_image_url varchar(2048);
