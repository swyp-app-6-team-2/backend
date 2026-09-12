package com.star_pick.starpick.domain.ingredient.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngredientNameIndexTest {

    private final IngredientNameIndex index = new IngredientNameIndex(Map.of("감자", 31L, "대파", 44L));

    @Test
    @DisplayName("재료명은 대소문자와 모든 공백을 무시해 정확히 매칭한다")
    void matchesNormalizedName() {
        assertThat(index.match(" 감 자 ")).isEqualTo(31L);
        assertThat(index.match(" 대\t파\n")).isEqualTo(44L);
    }

    @Test
    @DisplayName("없는 이름과 null 및 공백은 매칭하지 않는다")
    void doesNotMatchMissingOrBlankName() {
        assertThat(index.match("없는 재료")).isNull();
        assertThat(index.match(null)).isNull();
        assertThat(index.match("   ")).isNull();
    }
}
