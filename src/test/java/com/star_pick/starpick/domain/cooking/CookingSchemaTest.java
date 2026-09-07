package com.star_pick.starpick.domain.cooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * cook_history 매핑이 실제 PostgreSQL 스키마로 만들어졌는지 확인한다.
 *
 * <p>migration 도구가 없어 스키마를 ddl-auto 가 만든다. 제약은 Entity 매핑에 명시하고
 * 통합 테스트로 확인한다는 규칙(CLAUDE.md §3)을 이행하는 테스트다.
 */
@IntegrationTest
class CookingSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // RecipeSchemaTest·UploadObjectSchemaTest 와 같은 시그니처를 유지한다. 세 번째 복사라
    // 공용으로 뽑을 때가 됐지만 그러면 이 PR 밖의 테스트 두 개를 건드리게 되어 후속으로 남긴다.
    private String nullable(String table, String column) {
        return jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = ? and column_name = ?
                """, String.class, table, column);
    }

    @Test
    @DisplayName("cook_history 테이블이 만들어진다")
    void tableExists() {
        List<String> tables = jdbcTemplate.queryForList("""
                select table_name from information_schema.tables where table_name = 'cook_history'
                """, String.class);

        assertThat(tables).containsExactly("cook_history");
    }

    @Test
    @DisplayName("필수 컬럼은 NOT NULL 이고 사진·메모는 nullable 이다")
    void columnNullability() {
        assertThat(nullable("cook_history", "recipe_id")).isEqualTo("NO");
        assertThat(nullable("cook_history", "cooked_at")).isEqualTo("NO");
        assertThat(nullable("cook_history", "photo_key")).isEqualTo("YES");
        assertThat(nullable("cook_history", "memo")).isEqualTo("YES");
    }

    @Test
    @DisplayName("photo_key 에 UNIQUE 제약이 이름 그대로 있다")
    void photoKeyUniqueExists() {
        // 제약 이름이 바뀌면 위반을 409 로 번역하는 분기가 조용히 깨진다.
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.table_constraints
                where constraint_type = 'UNIQUE'
                  and table_name = 'cook_history'
                  and constraint_name = 'uk_cook_history_photo_key'
                """, Integer.class);

        assertThat(count).isOne();
    }

    @Test
    @DisplayName("recipe_id 조회용 인덱스가 있다")
    void recipeIdIndexExists() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes where indexname = 'idx_cook_history_recipe_id'
                """, String.class);

        assertThat(indexes).containsExactly("idx_cook_history_recipe_id");
    }

    @Test
    @DisplayName("cook_history.recipe_id 에는 FK 가 없다 — 도메인 경계 규칙의 의도된 결과")
    void recipeIdHasNoForeignKey() {
        // Cooking 은 Recipe Entity 를 참조할 수 없고 스칼라 컬럼에는 JPA 가 FK 를 만들지 않는다.
        // FK 는 migration 도구 도입(#11) 때 추가한다. 그때까지 허용하는 빈틈은 cooking.md §3.4 참고.
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name
                where tc.constraint_type = 'FOREIGN KEY'
                  and tc.table_name = 'cook_history' and kcu.column_name = 'recipe_id'
                """, Integer.class);

        assertThat(count).isZero();
    }
}
