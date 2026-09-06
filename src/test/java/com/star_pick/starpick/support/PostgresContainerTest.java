package com.star_pick.starpick.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트가 정말로 PostgreSQL 18 컨테이너에 붙어 있는지,
 * 이후 단계(Recipe 삭제 행 잠금, Ingestion Worker 선점)에서 쓸
 * 잠금 구문이 이 DB에서 실행 가능한지를 확인한다.
 */
@IntegrationTest
class PostgresContainerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void connectsToPostgres18() {
        Integer versionNum = jdbcTemplate.queryForObject(
                "SELECT current_setting('server_version_num')::int", Integer.class);

        assertThat(versionNum).isNotNull();
        assertThat(versionNum).isGreaterThanOrEqualTo(180000);
    }

    @Test
    void supportsSelectForUpdateSkipLocked() {
        jdbcTemplate.execute("CREATE TABLE lock_probe (id bigint PRIMARY KEY)");

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM lock_probe FOR UPDATE SKIP LOCKED", Long.class);

        assertThat(ids).isEmpty();
    }
}
