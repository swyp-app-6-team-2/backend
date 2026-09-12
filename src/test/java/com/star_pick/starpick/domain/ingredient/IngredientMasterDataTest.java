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
class IngredientMasterDataTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("확정 마스터 104행과 카테고리별 개수가 적재된다")
    void approvedMasterDataIsLoaded() {
        List<Map<String, Object>> counts = jdbcTemplate.queryForList("""
                select category_code, count(*)::int as count
                from ingredient
                group by category_code
                """);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ingredient", Integer.class)).isEqualTo(104);
        assertThat(counts)
                .extracting(row -> row.get("category_code"), row -> row.get("count"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("MEAT", 13),
                        org.assertj.core.groups.Tuple.tuple("SEAFOOD", 15),
                        org.assertj.core.groups.Tuple.tuple("VEGETABLE", 27),
                        org.assertj.core.groups.Tuple.tuple("SAUCE", 26),
                        org.assertj.core.groups.Tuple.tuple("ETC", 23));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ingredient where cardinality(aliases) > 0",
                Integer.class)).isEqualTo(15);
    }

    @Test
    @DisplayName("재료 name 은 104행 전체에서 유일하다")
    void ingredientNamesAreUnique() {
        List<String> duplicateNames = jdbcTemplate.queryForList("""
                select name
                from ingredient
                group by name
                having count(*) > 1
                """, String.class);

        assertThat(duplicateNames).isEmpty();
    }

    @Test
    @DisplayName("기존·신규 재료의 한글 이름과 text[] 별칭이 확정값 그대로 저장된다")
    void ingredientNamesAndAliasesRoundTrip() {
        Map<String, Object> existing = jdbcTemplate.queryForMap("""
                select name, aliases[1] as first_alias, aliases[2] as second_alias
                from ingredient where code = 'MET001'
                """);
        Map<String, Object> meat = jdbcTemplate.queryForMap("""
                select name, aliases[1] as first_alias, aliases[2] as second_alias,
                       aliases[3] as third_alias
                from ingredient where code = 'MET013'
                """);
        Map<String, Object> sauce = jdbcTemplate.queryForMap("""
                select name, aliases[1] as first_alias, aliases[2] as second_alias
                from ingredient where code = 'SAU026'
                """);

        assertThat(existing)
                .containsEntry("name", "돼지고기(삼겹살)")
                .containsEntry("first_alias", "삼겹살")
                .containsEntry("second_alias", "돼지고기");
        assertThat(jdbcTemplate.queryForObject("""
                select cardinality(aliases) from ingredient where code = 'MET008'
                """, Integer.class)).isZero();
        assertThat(meat)
                .containsEntry("name", "쇠고기(차돌박이)")
                .containsEntry("first_alias", "차돌박이")
                .containsEntry("second_alias", "쇠고기")
                .containsEntry("third_alias", "소고기");
        assertThat(sauce)
                .containsEntry("name", "올리고당/물엿")
                .containsEntry("first_alias", "올리고당")
                .containsEntry("second_alias", "물엿");
    }
}
