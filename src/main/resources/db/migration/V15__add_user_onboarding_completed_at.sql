ALTER TABLE users ADD COLUMN onboarding_completed_at TIMESTAMP;

-- 기존 회원은 온보딩을 생략한다. 실제 완료 이력이 아니라 적용 시점의 간주 처리다.
UPDATE users SET onboarding_completed_at = CURRENT_TIMESTAMP;
-- 기본값을 두지 않아 이후 신규 가입자는 NULL(미완료)로 생성된다.
