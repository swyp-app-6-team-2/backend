-- 기존 사용자와 기존 insert 경로는 MEMBER로 유지한다.
alter table users add column account_type varchar(16) not null default 'MEMBER';
alter table users add constraint ck_users_account_type
    check (account_type in ('GUEST', 'MEMBER'));

-- 게스트에게 회원가입 완료 이력을 만들지 않는다.
alter table users alter column signup_completed_at drop not null;
alter table users add constraint ck_users_signup_completion
    check ((account_type = 'GUEST' and signup_completed_at is null)
        or (account_type = 'MEMBER' and signup_completed_at is not null));
