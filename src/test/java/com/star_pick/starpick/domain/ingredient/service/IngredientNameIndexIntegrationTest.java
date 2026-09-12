package com.star_pick.starpick.domain.ingredient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IngredientNameIndexIntegrationTest {

    @Autowired
    private IngredientService ingredientService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestFixtures fixtures;

    @AfterEach
    void restoreIngredientActivity() {
        fixtures.restoreIngredientActivity();
    }

    @Test
    @DisplayName("여러 활성 재료가 공유하는 돼지고기 별칭은 자동 연결하지 않는다")
    void doesNotMatchAmbiguousAlias() {
        IngredientNameIndex index = ingredientService.loadNameIndex();

        assertThat(index.match("돼지고기")).isNull();
        assertThat(index.match("삼겹살")).isEqualTo(fixtures.ingredientId("MET001"));
    }

    @Test
    @DisplayName("비활성 재료는 이름 색인에서 제외한다")
    void excludesInactiveIngredient() {
        jdbcTemplate.update("update ingredient set active = false where code = 'VEG007'");

        IngredientNameIndex index = ingredientService.loadNameIndex();

        assertThat(index.match("감자")).isNull();
    }
}
