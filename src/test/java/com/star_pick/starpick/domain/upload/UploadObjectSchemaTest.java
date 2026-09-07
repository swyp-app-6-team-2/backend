package com.star_pick.starpick.domain.upload;

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
 * 통합 테스트로 확인한다는 규칙(CLAUDE.md §3)을 이행한다. {@code RecipeSchemaTest} 와 같은 방식이다.
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
    @DisplayName("user_id 에는 FK 가 없다 — 도메인 경계 규칙의 의도된 결과")
    void userIdHasNoForeignKey() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name
                where tc.constraint_type = 'FOREIGN KEY'
                  and tc.table_name = 'upload_object' and kcu.column_name = 'user_id'
                """, Integer.class);

        assertThat(count).isZero();
    }
}
