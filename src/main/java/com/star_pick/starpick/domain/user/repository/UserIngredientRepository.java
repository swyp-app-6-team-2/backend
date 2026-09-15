package com.star_pick.starpick.domain.user.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserIngredientRepository {
    private final JdbcTemplate jdbc;

    public List<Long> findIngredientIds(Long userId) {
        return jdbc.queryForList("select ingredient_id from user_ingredient where user_id = ?", Long.class, userId);
    }

    /** UNIQUE를 최종 방어로 삼고 실제 추가된 경우만 true를 반환한다. */
    public boolean insertIfAbsent(Long userId, Long ingredientId) {
        return !jdbc.queryForList("""
                insert into user_ingredient (user_id, ingredient_id) values (?, ?)
                on conflict (user_id, ingredient_id) do nothing returning ingredient_id
                """, Long.class, userId, ingredientId).isEmpty();
    }
}
