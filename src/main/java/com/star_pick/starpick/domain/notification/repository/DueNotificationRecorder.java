package com.star_pick.starpick.domain.notification.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이번 분의 발송 대상을 조회하면서 {@code push_log} 에 PROCESSING 으로 기록하고, 새로 기록된 것만 돌려준다.
 *
 * <p>JPA 대신 {@code JdbcClient} 를 쓰는 이유: 데이터 변경 CTE 의 결과를 행으로 받아야 해서
 * {@code @Modifying} 쿼리로 표현할 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class DueNotificationRecorder {

    // due: distinct on 으로 토큰당 1행. 같은 시각이 두 번 들어가 있으면 마지막 join 이 2행을 돌려줘 두 번 발송된다.
    // inserted: 같은 분에 두 번 실행되면 UNIQUE(push_token_id, scheduled_at) 에 막혀 아무것도 돌려주지 않는다.
    private static final String RECORD_DUE = """
            with due as (
                select distinct on (t.id) s.user_id, t.id as push_token_id, t.token, slot ->> 'label' as label
                  from notification_setting s
                  cross join lateral jsonb_array_elements(s.time_slots) slot
                  join push_token t on t.user_id = s.user_id and t.active
                 where s.enabled
                   and cast(:weekday as text) = any (s.weekdays)
                   and slot ->> 'time' = cast(:time as text)
                 order by t.id
            ), inserted as (
                insert into push_log (user_id, push_token_id, scheduled_at, status)
                select user_id, push_token_id, cast(:scheduledAt as timestamptz), 'PROCESSING' from due
                on conflict (push_token_id, scheduled_at) do nothing
                returning id, push_token_id
            )
            select inserted.id, inserted.push_token_id, due.token, due.label
              from inserted join due using (push_token_id)
            """;

    private final JdbcClient jdbcClient;

    @Transactional
    public List<DueNotification> record(String weekday, String time, Instant scheduledAt) {
        return jdbcClient.sql(RECORD_DUE)
                .param("weekday", weekday)
                .param("time", time)
                .param("scheduledAt", OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC))
                .query((rs, rowNum) -> new DueNotification(
                        rs.getLong("id"), rs.getLong("push_token_id"), rs.getString("token"), rs.getString("label")))
                .list();
    }
}
