package com.star_pick.starpick.domain.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Entity 매핑이 실제 PostgreSQL 스키마로 만들어졌는지 확인한다.
 *
 * <p>{@code ddl-auto: validate} 가 보지 않는 nullable·PK·FK 를 실제 스키마를 조회해 확인한다.
 * {@code RecipeSchemaTest} 와 같은 방식이다.
 */
@IntegrationTest
class UploadObjectSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    @DisplayName("object_key 가 기본키다")
    void objectKeyIsPrimaryKey() {
        List<String> columns = jdbcTemplate.queryForList("""
                select kcu.column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name
                where tc.constraint_type = 'PRIMARY KEY' and tc.table_name = 'upload_object'
                """, String.class);

        assertThat(columns).containsExactly("object_key");
    }

    @Test
    @DisplayName("소유·용도 컬럼은 NOT NULL 이다")
    void requiredColumnsAreNotNull() {
        assertThat(nullable("upload_object", "user_id")).isEqualTo("NO");
        assertThat(nullable("upload_object", "purpose")).isEqualTo("NO");
    }

    @Test
    @DisplayName("attached_at 은 nullable 이다 — null 이 미연결을 뜻한다")
    void attachedAtIsNullable() {
        assertThat(nullable("upload_object", "attached_at")).isEqualTo("YES");
    }

    @Test
    @DisplayName("upload_object.user_id 는 users.user_id 를 참조한다")
    void userIdReferencesUsers() {
        assertThat(foreignKey("fk_upload_object_user"))
                .containsEntry("column_name", "user_id")
                .containsEntry("referenced_table", "users")
                .containsEntry("referenced_column", "user_id");
    }
}
