-- profile_image_url의 nullable 변경은 main의 V20에서 이미 적용됨.
alter table profiles add column profile_image_key varchar(255);

-- 기존 URL은 보존하며, 프로필이 없는 기존 회원만 보완한다.
insert into profiles (user_id, nickname, created_at, updated_at)
select u.user_id, '스타' || lpad(floor(random() * 10000)::text, 4, '0'), now(), now()
from users u where not exists (select 1 from profiles p where p.user_id = u.user_id);

alter table upload_object drop constraint ck_upload_object_purpose;
alter table upload_object add constraint ck_upload_object_purpose
check (purpose in ('RECIPE_COVER', 'COOK_HISTORY_PHOTO', 'INGESTION_INPUT', 'INQUIRY_ATTACHMENT', 'PROFILE_IMAGE'));
