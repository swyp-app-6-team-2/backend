-- 목록 조회(GET /api/v1/recipes)의 유일한 필터가 user_id 다. V1 의 recipe 인덱스는 PK 뿐이라
-- 인덱스 없이는 매 조회가 seq scan 이 된다.
--
-- created_at 과 id 를 함께 넣는 이유는 정렬 때문이다. 목록은 (created_at, id) 로 정렬하며
-- PostgreSQL 은 이 인덱스를 역방향으로도 스캔할 수 있어 최신순·오래된순을 모두 커버한다.
create index idx_recipe_user_id_created_at on recipe (user_id, created_at, id);
