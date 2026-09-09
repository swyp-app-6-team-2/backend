package com.star_pick.starpick.domain.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
import java.util.Map;
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
                "id", "code", "name", "category_code", "aliases", "active");
        for (String column : columns) {
            assertThat(columnAttribute(column, "is_nullable")).isEqualTo("NO");
        }
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

    @Test
    @DisplayName("PM 확정 87행과 카테고리별 개수, 13개 별칭 행이 적재된다")
    void seedDataIsLoaded() {
        List<Map<String, Object>> counts = jdbcTemplate.queryForList("""
                select category_code, count(*)::int as count
                from ingredient
                group by category_code
                """);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ingredient", Integer.class)).isEqualTo(87);
        assertThat(counts)
                .extracting(row -> row.get("category_code"), row -> row.get("count"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("MEAT", 12),
                        org.assertj.core.groups.Tuple.tuple("SEAFOOD", 11),
                        org.assertj.core.groups.Tuple.tuple("VEGETABLE", 24),
                        org.assertj.core.groups.Tuple.tuple("SAUCE", 22),
                        org.assertj.core.groups.Tuple.tuple("ETC", 18));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ingredient where cardinality(aliases) > 0",
                Integer.class)).isEqualTo(13);
    }

    @Test
    @DisplayName("한글 이름과 text[] 별칭이 원문 그대로 저장된다")
    void koreanNameAndAliasesRoundTrip() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select name, aliases[1] as first_alias, aliases[2] as second_alias
                from ingredient where code = 'MET001'
                """);

        assertThat(row)
                .containsEntry("name", "돼지고기(삼겹살)")
                .containsEntry("first_alias", "삼겹살")
                .containsEntry("second_alias", "돼지고기");
        assertThat(jdbcTemplate.queryForObject("""
                select cardinality(aliases) from ingredient where code = 'MET008'
                """, Integer.class)).isZero();
    }
}
