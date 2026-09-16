-- 복구 대상만 user_id 커서로 순회한다. 정상 계정은 인덱스에 포함하지 않는다.
create index idx_users_pending_withdrawal on users (user_id) where deleted_at is not null;
