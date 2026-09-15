ALTER TABLE users
    ADD COLUMN recipe_slot_limit INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN active_recipe_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cumulative_recipe_count INTEGER NOT NULL DEFAULT 0;