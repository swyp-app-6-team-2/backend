-- FK 없이 남아 있던 마지막 참조다(이슈 #117). 레시피 삭제와 조리 기록 생성이 겹치면
-- 없는 레시피를 가리키는 행이 남는다.
--
-- on delete cascade 는 쓰지 않는다. DB 가 조리 기록을 지우면 photo_key 로 GCS 를 정리할
-- 기회를 잃는다. 삭제 순서는 지금처럼 애플리케이션이 지키고(자식 먼저 -> 부모)
-- FK 는 그 순서를 어기지 못하게만 한다.
--
-- 적용 전 dev 에서 고아 행 0건을 확인했다(2026-09-18).
alter table cook_history
    add constraint fk_cook_history_recipe
        foreign key (recipe_id) references recipe (id);
