-- 사건 시각을 절대 시각(timestamp with time zone)으로 통일한다. 이슈 #68.
-- 기존 값은 서버 JVM 기본 시간대(UTC)로 기록됐으므로 UTC 로 해석한다.
-- 세션 시간대에 기대지 않도록 AT TIME ZONE 'UTC' 를 명시한다(로컬 KST 에서 돌려도 같은 결과).

alter table users
    alter column signup_completed_at     type timestamp(6) with time zone using signup_completed_at at time zone 'UTC',
    alter column marketing_agreed_at     type timestamp(6) with time zone using marketing_agreed_at at time zone 'UTC',
    alter column last_login_at           type timestamp(6) with time zone using last_login_at at time zone 'UTC',
    alter column created_at              type timestamp(6) with time zone using created_at at time zone 'UTC',
    alter column deleted_at              type timestamp(6) with time zone using deleted_at at time zone 'UTC',
    alter column service_agreed_at       type timestamp(6) with time zone using service_agreed_at at time zone 'UTC',
    alter column age_over_14_agreed_at   type timestamp(6) with time zone using age_over_14_agreed_at at time zone 'UTC',
    alter column last_activity_at        type timestamp(6) with time zone using last_activity_at at time zone 'UTC',
    alter column onboarding_completed_at type timestamp(6) with time zone using onboarding_completed_at at time zone 'UTC';

alter table profiles
    alter column created_at type timestamp(6) with time zone using created_at at time zone 'UTC',
    alter column updated_at type timestamp(6) with time zone using updated_at at time zone 'UTC';

alter table social_credentials
    alter column created_at type timestamp(6) with time zone using created_at at time zone 'UTC';

alter table refresh_tokens
    alter column expires_at type timestamp(6) with time zone using expires_at at time zone 'UTC',
    alter column created_at type timestamp(6) with time zone using created_at at time zone 'UTC';

alter table recipe
    alter column created_at type timestamp(6) with time zone using created_at at time zone 'UTC',
    alter column updated_at type timestamp(6) with time zone using updated_at at time zone 'UTC';

alter table upload_object
    alter column attached_at type timestamp(6) with time zone using attached_at at time zone 'UTC';
