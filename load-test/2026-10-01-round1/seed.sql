-- 1회차 테스트 데이터. 게스트 userId 목록을 psql 변수 ids 로 받는다(예: -v ids='{101,102}').
-- 출력은 "userId,ingestionJobId" 줄이며, README 의 명령이 이것을 guests.json 에 합친다.

-- 게스트도 저장 한도가 10개다. 레시피 20개와 쓰기 시나리오 반복분을 받으려고 올린다.
-- 삭제해도 한도는 돌아오지 않는다(누적 저장 수 기준).
UPDATE users SET recipe_slot_limit = 1000 WHERE user_id = ANY (:'ids'::bigint[]);

-- 분석 상태 조회(A6)용 Job. 이미 끝난 FAILED 상태라 분석 Worker 가 집어 가지 않는다.
INSERT INTO ingestion_job (user_id, source_type, input_url, status, failure_code, attempt, created_at)
SELECT u, 'YOUTUBE', 'https://www.youtube.com/watch?v=loadtest0001', 'FAILED', 'SOURCE_UNAVAILABLE', 1, now()
FROM unnest(:'ids'::bigint[]) AS u
RETURNING user_id, id;
