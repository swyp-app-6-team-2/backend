package com.star_pick.starpick.domain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Entity 매핑이 실제 PostgreSQL 스키마로 만들어졌는지 확인한다.
 *
 * <p>스키마는 Flyway 가 만들고 {@code ddl-auto: validate} 는 컬럼과 타입만 본다. validate 가 보지
 * 않는 nullable·UNIQUE·인덱스·FK 를 여기서 실제 스키마를 조회해 확인한다.
 */
@IntegrationTest
class RecipeSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    private String nullable(String table, String column) {
        return jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = ? and column_name = ?
                """, String.class, table, column);
    }

    /** 이름으로 FK 제약을 찾아 참조 컬럼·참조 대상을 돌려준다. */
    private Map<String, Object> foreignKey(String constraintName) {
        return jdbcTemplate.queryForMap("""
                select kcu.column_name, ccu.table_name as referenced_table,
                       ccu.column_name as referenced_column
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_schema = kcu.constraint_schema
                 and tc.constraint_name = kcu.constraint_name
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_schema = ccu.constraint_schema
                 and tc.constraint_name = ccu.constraint_name
                where tc.constraint_schema = 'public'
                  and tc.constraint_type = 'FOREIGN KEY'
                  and tc.constraint_name = ?
                """, constraintName);
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
    @DisplayName("recipe_ingredient.ingredient_id 는 ingredient.id 를 참조한다")
    void recipeIngredientReferencesIngredientMaster() {
        assertThat(foreignKey("fk_recipe_ingredient_ingredient"))
                .containsEntry("column_name", "ingredient_id")
                .containsEntry("referenced_table", "ingredient")
                .containsEntry("referenced_column", "id");
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

    /** 목록 조회의 유일한 필터가 user_id 다. 인덱스가 사라지면 매 조회가 seq scan 이 된다. */
    @Test
    @DisplayName("목록 조회용 인덱스가 (user_id, created_at, id) 로 만들어진다")
    void listIndexExists() {
        String definition = jdbcTemplate.queryForObject("""
                select indexdef from pg_indexes
                where tablename = 'recipe' and indexname = 'idx_recipe_user_id_created_at'
                """, String.class);

        assertThat(definition).contains("(user_id, created_at, id)");
    }

    @Test
    @DisplayName("recipe.user_id 는 users.user_id 를 참조한다")
    void userIdReferencesUsers() {
        assertThat(foreignKey("fk_recipe_user"))
                .containsEntry("column_name", "user_id")
                .containsEntry("referenced_table", "users")
                .containsEntry("referenced_column", "user_id");
    }

    private void insertRecipe(String registrationMethod, Long ingestionJobId, String sourceUrl,
                              String[] sourceImageKeys) {
        jdbcTemplate.update("""
                insert into recipe (user_id, title, category_code, registration_method, servings,
                                    created_at, updated_at, ingestion_job_id, source_url, source_image_keys)
                values (1, '김치찌개', 'KOREAN', ?, 1, now(), now(), ?, ?, ?)
                """, registrationMethod, ingestionJobId, sourceUrl, sourceImageKeys);
    }

    @Test
    @DisplayName("출처 컬럼 3개는 nullable 이고 사진 Key 는 text 배열이다")
    void sourceColumnsAreNullable() {
        assertThat(nullable("recipe", "ingestion_job_id")).isEqualTo("YES");
        assertThat(nullable("recipe", "source_url")).isEqualTo("YES");
        assertThat(nullable("recipe", "source_image_keys")).isEqualTo("YES");
        assertThat(jdbcTemplate.queryForObject("""
                select udt_name from information_schema.columns
                where table_name = 'recipe' and column_name = 'source_image_keys'
                """, String.class)).isEqualTo("_text");
    }

    @Test
    @DisplayName("ingestion_job_id 는 UNIQUE 이고 ingestion_job 을 참조한다")
    void ingestionJobIdIsUniqueForeignKey() {
        assertThat(jdbcTemplate.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = 'uk_recipe_ingestion_job_id' and contype = 'u'
                """, String.class)).isEqualTo("UNIQUE (ingestion_job_id)");
        assertThat(foreignKey("fk_recipe_ingestion_job"))
                .containsEntry("column_name", "ingestion_job_id")
                .containsEntry("referenced_table", "ingestion_job")
                .containsEntry("referenced_column", "id");
    }

    @Test
    @DisplayName("등록 방식과 출처 컬럼의 조합을 CHECK 가 강제한다")
    void sourceMatchesRegistrationMethod() {
        fixtures.reset();
        Long first = fixtures.saveReadyUrlJob(1L, "https://www.youtube.com/watch?v=a");
        Long second = fixtures.saveReadyUrlJob(1L, "https://www.youtube.com/watch?v=b");
        Long unused = fixtures.saveReadyUrlJob(1L, "https://www.youtube.com/watch?v=c");
        String[] keys = {"ingestion-inputs/1/a.jpg"};

        assertThatCode(() -> insertRecipe("MANUAL", null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> insertRecipe("URL", first, "https://www.youtube.com/watch?v=a", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertRecipe("IMAGE", second, null, keys)).doesNotThrowAnyException();

        assertThatThrownBy(() -> insertRecipe("MANUAL", unused, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_recipe_source");
        assertThatThrownBy(() -> insertRecipe("URL", unused, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_recipe_source");
        assertThatThrownBy(() -> insertRecipe("URL", unused, "https://x", keys))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_recipe_source");
        assertThatThrownBy(() -> insertRecipe("IMAGE", unused, null, new String[0]))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_recipe_source");
        assertThatThrownBy(() -> insertRecipe("IMAGE", null, null, keys))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_recipe_source");
    }
}
