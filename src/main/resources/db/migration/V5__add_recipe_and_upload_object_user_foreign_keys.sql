-- recipe.user_id, upload_object.user_id 는 Flyway 도입(#11) 때 같이 걸었어야 했는데 빠졌다.
-- 둘 다 users 를 참조하는 스칼라 컬럼이라 바로 건다(이슈 #30).
alter table recipe
    add constraint fk_recipe_user
        foreign key (user_id) references users (user_id);

alter table upload_object
    add constraint fk_upload_object_user
        foreign key (user_id) references users (user_id);
