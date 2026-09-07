package com.star_pick.starpick.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트 컨테이너의 스키마를 Flyway 가 만들었는지 지킨다(이슈 #11).
 *
 * <p>전체 테스트가 깨졌을 때 "Flyway 가 안 돌았다"와 "돌았는데 Entity 와 어긋난다"를 구분해 준다.
 */
@IntegrationTest
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 가 V1 부터 실패 없이 적용했다")
    void migrationsApplied() {
        // 버전 목록을 통째로 단정하지 않는다. migration 을 추가할 때마다 고쳐야 하는 테스트가 된다.
        List<String> versions = jdbcTemplate.queryForList("""
                select version from flyway_schema_history
                where version is not null
                order by installed_rank
                """, String.class);

        assertThat(versions).startsWith("1");

        Integer failed = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);

        assertThat(failed).isZero();
    }

    @Test
    @DisplayName("migration 이 도메인 테이블 8개를 모두 만들었다")
    void migrationCreatedAllTables() {
        // 정확히 일치는 쓸 수 없다. PostgresContainerTest 의 lock_probe 가 같은 컨테이너에 남는다.
        List<String> tables = jdbcTemplate.queryForList("""
                select table_name from information_schema.tables where table_schema = 'public'
                """, String.class);

        assertThat(tables).contains(
                "cook_history",
                "profiles",
                "recipe",
                "recipe_ingredient",
                "recipe_step",
                "social_credentials",
                "upload_object",
                "users");
    }
}
