package com.star_pick.starpick.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 사건 시각 컬럼이 전부 {@code timestamp with time zone} 인지 확인한다.
 *
 * <p>{@code timestamp without time zone} 은 서버 시계 기준 숫자만 저장해 서버 시간대 설정이 바뀌면
 * 기존 값의 뜻이 밀린다. {@code ddl-auto: validate} 는 이 차이를 막지 않는다.
 * 깨졌다면 고칠 곳은 이 테스트가 아니라 새 migration 이다.
 */
@IntegrationTest
class TimestampColumnTypeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("시간대 없는 timestamp 컬럼이 없다")
    void noTimestampWithoutTimeZone() {
        // flyway_schema_history 는 Flyway 가 만드는 테이블이라 제외한다.
        List<String> columns = jdbcTemplate.queryForList("""
                select table_name || '.' || column_name
                from information_schema.columns
                where table_schema = 'public'
                  and data_type = 'timestamp without time zone'
                  and table_name <> 'flyway_schema_history'
                order by 1
                """, String.class);

        assertThat(columns).isEmpty();
    }
}
