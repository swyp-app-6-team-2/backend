package com.star_pick.starpick.domain.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IngredientSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** {@code ingredient.<column>} 의 information_schema 속성 하나를 읽는다. */
    private String columnAttribute(String column, String attribute) {
        return jdbcTemplate.queryForObject("""
                select %s from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'ingredient'
                  and column_name = ?
                """.formatted(attribute), String.class, column);
    }

    @Test
    @DisplayName("ingredient 테이블과 필수 컬럼이 만들어진다")
    void tableAndColumnsExist() {
        List<String> columns = jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'ingredient'
                order by ordinal_position
                """, String.class);

        assertThat(columns).containsExactly(
                "id", "code", "name", "category_code", "aliases", "active", "icon_key");
        for (String column : columns) {
            assertThat(columnAttribute(column, "is_nullable")).isEqualTo("NO");
        }
    }

    @Test
    @DisplayName("icon_key 는 255자 varchar 다")
    void iconKeyIsVarchar255() {
        assertThat(columnAttribute("icon_key", "data_type")).isEqualTo("character varying");
        assertThat(columnAttribute("icon_key", "character_maximum_length")).isEqualTo("255");
    }

    @Test
    @DisplayName("aliases 는 기본값이 빈 배열인 PostgreSQL text[] 다")
    void aliasesIsNonNullTextArrayWithEmptyDefault() {
        assertThat(columnAttribute("aliases", "data_type")).isEqualTo("ARRAY");
        assertThat(columnAttribute("aliases", "udt_name")).isEqualTo("_text");
        assertThat(columnAttribute("aliases", "column_default")).isEqualTo("'{}'::text[]");
        assertThat(columnAttribute("active", "column_default")).isEqualTo("true");
    }

    @Test
    @DisplayName("PK, code UNIQUE, category CHECK 제약이 있다")
    void constraintsExist() {
        List<String> constraints = jdbcTemplate.queryForList("""
                select constraint_name from information_schema.table_constraints
                where table_schema = 'public' and table_name = 'ingredient'
                """, String.class);

        assertThat(constraints).contains(
                "pk_ingredient",
                "uk_ingredient_code",
                "ck_ingredient_category_code");
    }

}
