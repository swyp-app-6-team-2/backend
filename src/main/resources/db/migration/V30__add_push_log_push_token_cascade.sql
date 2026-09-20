-- push_token 은 같은 기기 토큰을 다른 사용자가 등록하면 행 id 를 유지한 채 소유자만 바뀐다.
-- 그래서 이전 소유자가 남긴 push_log 가 새 소유자의 토큰을 참조하게 되고, 탈퇴 정리가
-- user_id 기준으로만 지우면 FK 위반으로 탈퇴 전체가 500 으로 끝났다(이슈 #126).
-- 정리 순서를 코드에서 맞추는 대신 스키마가 관계를 책임지게 한다.
alter table push_log drop constraint fk_push_log_push_token;
alter table push_log
    add constraint fk_push_log_push_token foreign key (push_token_id)
        references push_token (id) on delete cascade;
