package com.star_pick.starpick.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IngestionSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("ingestion_job 테이블의 컬럼과 null 허용 계약이 일치한다")
    void columnsAndNullabilityMatchContract() {
        List<String> columns = jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'ingestion_job'
                order by ordinal_position
                """, String.class);

        assertThat(columns).containsExactly(
                "id", "user_id", "source_type", "input_url", "input_image_keys",
                "status", "result", "failure_code", "attempt", "created_at",
                "started_at", "expires_at", "consumed_at");
        assertThat(nullableColumns()).containsExactlyInAnyOrder(
                "input_url", "input_image_keys", "result", "failure_code",
                "started_at", "expires_at", "consumed_at");
    }

    @Test
    @DisplayName("사용자 FK와 선점·일일 한도 인덱스가 존재한다")
    void foreignKeyAndIndexesExist() {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.table_constraints
                where table_schema = 'public' and table_name = 'ingestion_job'
                  and constraint_name = 'fk_ingestion_job_user'
                """, Integer.class)).isOne();

        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes
                where schemaname = 'public' and tablename = 'ingestion_job'
                """, String.class);
        assertThat(indexes).contains(
                "idx_ingestion_job_queued", "idx_ingestion_job_user_created_at");
        assertThat(indexDefinition("idx_ingestion_job_queued"))
                .contains("WHERE ((status)::text = 'QUEUED'::text)");
    }

    @Test
    @DisplayName("JSON 결과와 이미지 Key는 PostgreSQL 전용 타입을 사용한다")
    void postgresTypesMatchContract() {
        assertThat(columnAttribute("result", "udt_name")).isEqualTo("jsonb");
        assertThat(columnAttribute("input_image_keys", "udt_name")).isEqualTo("_text");
        for (String column : List.of("created_at", "started_at", "expires_at", "consumed_at")) {
            assertThat(columnAttribute(column, "data_type")).isEqualTo("timestamp with time zone");
        }
    }

    @Test
    @DisplayName("IMAGE 입력은 URL 없이 한 개 이상의 이미지 Key만 허용한다")
    void imageInputCheckConstraint() {
        long userId = seedUser();

        assertThatThrownBy(() -> insertJob(userId, "IMAGE", "https://example.com", new String[]{"a"}))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJob(userId, "IMAGE", null, new String[]{}))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertJob(userId, "IMAGE", null, new String[]{"a"})).isOne();
    }

    @Test
    @DisplayName("URL 출처는 URL만 허용한다")
    void urlInputCheckConstraint() {
        long userId = seedUser();

        assertThatThrownBy(() -> insertJob(userId, "YOUTUBE", "https://youtu.be/test", new String[]{"a"}))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertJob(userId, "YOUTUBE", "https://youtu.be/test", null)).isOne();
    }

    private List<String> nullableColumns() {
        return jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'ingestion_job'
                  and is_nullable = 'YES'
                """, String.class);
    }

    private String columnAttribute(String column, String attribute) {
        return jdbcTemplate.queryForObject("""
                select %s from information_schema.columns
                where table_schema = 'public' and table_name = 'ingestion_job'
                  and column_name = ?
                """.formatted(attribute), String.class, column);
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = 'public' and tablename = 'ingestion_job' and indexname = ?
                """, String.class, indexName);
    }

    private long seedUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (service_terms_agreed, privacy_agreed, marketing_agreed,
                                   signup_completed_at, created_at)
                values (true, true, true, now(), now())
                returning user_id
                """, Long.class);
        return id;
    }

    private int insertJob(long userId, String sourceType, String inputUrl, String[] inputImageKeys) {
        return jdbcTemplate.update("""
                insert into ingestion_job
                    (user_id, source_type, input_url, input_image_keys, status, attempt, created_at)
                values (?, ?, ?, ?, 'QUEUED', 0, now())
                """, userId, sourceType, inputUrl, inputImageKeys);
    }
}
