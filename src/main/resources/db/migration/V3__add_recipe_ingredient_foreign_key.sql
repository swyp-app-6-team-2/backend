alter table recipe_ingredient
    add constraint fk_recipe_ingredient_ingredient
        foreign key (ingredient_id) references ingredient (id);
