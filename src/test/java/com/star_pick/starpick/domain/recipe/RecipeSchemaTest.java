package com.star_pick.starpick.domain.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Entity 매핑이 실제 PostgreSQL 스키마로 만들어졌는지 확인한다.
 *
 * <p>migration 도구가 없어 스키마를 ddl-auto 가 만든다. 제약은 Entity 매핑에 명시하고
 * 통합 테스트로 확인한다는 규칙(CLAUDE.md §3)을 이행하는 테스트다.
 */
@IntegrationTest
class RecipeSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String nullable(String table, String column) {
        return jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = ? and column_name = ?
                """, String.class, table, column);
    }

    @Test
    @DisplayName("Recipe 계열 테이블 3개가 만들어진다")
    void tablesExist() {
        List<String> tables = jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_name in ('recipe', 'recipe_ingredient', 'recipe_step')
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("recipe", "recipe_ingredient", "recipe_step");
    }

    @Test
    @DisplayName("Recipe 의 필수 컬럼은 NOT NULL 이다")
    void recipeRequiredColumnsAreNotNull() {
        assertThat(nullable("recipe", "user_id")).isEqualTo("NO");
        assertThat(nullable("recipe", "title")).isEqualTo("NO");
        assertThat(nullable("recipe", "category_code")).isEqualTo("NO");
        assertThat(nullable("recipe", "registration_method")).isEqualTo("NO");
        assertThat(nullable("recipe", "servings")).isEqualTo("NO");
        assertThat(nullable("recipe", "created_at")).isEqualTo("NO");
        assertThat(nullable("recipe", "updated_at")).isEqualTo("NO");
    }

    @Test
    @DisplayName("선택 컬럼은 nullable 이다")
    void optionalColumnsAreNullable() {
        assertThat(nullable("recipe", "cover_image_key")).isEqualTo("YES");
        assertThat(nullable("recipe", "cook_time_minutes")).isEqualTo("YES");
        assertThat(nullable("recipe", "memo")).isEqualTo("YES");
        assertThat(nullable("recipe_ingredient", "amount_text")).isEqualTo("YES");
        assertThat(nullable("recipe_ingredient", "ingredient_id")).isEqualTo("YES");
    }

    @Test
    @DisplayName("자식 테이블의 소유 FK 와 표시 순서는 NOT NULL 이다")
    void childOwnershipColumnsAreNotNull() {
        assertThat(nullable("recipe_ingredient", "recipe_id")).isEqualTo("NO");
        assertThat(nullable("recipe_ingredient", "name")).isEqualTo("NO");
        assertThat(nullable("recipe_ingredient", "display_order")).isEqualTo("NO");
        assertThat(nullable("recipe_step", "recipe_id")).isEqualTo("NO");
        assertThat(nullable("recipe_step", "content")).isEqualTo("NO");
        assertThat(nullable("recipe_step", "display_order")).isEqualTo("NO");
    }

    @Test
    @DisplayName("자식 테이블에 Recipe FK 제약이 있다")
    void childTablesHaveForeignKey() {
        List<String> tables = jdbcTemplate.queryForList("""
                select tc.table_name
                from information_schema.table_constraints tc
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_name = ccu.constraint_name
                where tc.constraint_type = 'FOREIGN KEY' and ccu.table_name = 'recipe'
                order by tc.table_name
                """, String.class);

        assertThat(tables).containsExactly("recipe_ingredient", "recipe_step");
    }

    @Test
    @DisplayName("자식 조회용 인덱스가 있다")
    void childIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes
                where indexname in ('idx_recipe_ingredient_recipe_id', 'idx_recipe_step_recipe_id')
                order by indexname
                """, String.class);

        assertThat(indexes).containsExactly("idx_recipe_ingredient_recipe_id", "idx_recipe_step_recipe_id");
    }

    @Test
    @DisplayName("자식 테이블에 (recipe_id, display_order) UNIQUE 가 없어야 한다")
    void childTablesMustNotHaveOrderUniqueConstraint() {
        // Hibernate 는 컬렉션 전체 교체를 insert 먼저, delete 나중에 flush 한다(실측 확인).
        // 그래서 이 UNIQUE 가 있으면 재료 3개를 2개로 줄이는 평범한 수정이 제약 위반으로 500 이 된다.
        // 실제로 로컬 개발 DB 에 이 제약이 남아 있어 그 증상을 겪었다.
        // displayOrder 는 외부에 노출되지 않는 파생값이라 중복이 생겨도 정렬만 흔들린다.
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name
                where tc.constraint_type = 'UNIQUE'
                  and tc.table_name in ('recipe_ingredient', 'recipe_step')
                  and kcu.column_name = 'display_order'
                """, Integer.class);

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("recipe.user_id 에는 FK 가 없다 — 도메인 경계 규칙의 의도된 결과")
    void userIdHasNoForeignKey() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name
                where tc.constraint_type = 'FOREIGN KEY'
                  and tc.table_name = 'recipe' and kcu.column_name = 'user_id'
                """, Integer.class);

        assertThat(count).isZero();
    }
}
