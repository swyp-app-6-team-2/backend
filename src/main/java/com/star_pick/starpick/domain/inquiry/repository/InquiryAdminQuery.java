package com.star_pick.starpick.domain.inquiry.repository;

import com.star_pick.starpick.domain.inquiry.domain.InquiryStatus;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면 조회.
 *
 * <p>작성자 닉네임·로그인 수단·탈퇴 여부는 Account 도메인 데이터라 Entity·Repository 를 참조하지 않고
 * 테이블을 읽기 전용으로 조인한다. 필터는 비어 있으면 전체다.
 */
@Repository
@RequiredArgsConstructor
public class InquiryAdminQuery {

    private static final String FILTER = """
            where (cast(:type as varchar) is null or i.type = cast(:type as varchar))
              and (cast(:answered as boolean) is null or (i.answer is not null) = cast(:answered as boolean))
            """;

    private final JdbcClient jdbcClient;

    public long count(InquiryStatus status, InquiryType type) {
        return jdbcClient.sql("select count(*) from inquiry i " + FILTER)
                .param("type", typeParam(type))
                .param("answered", answeredParam(status))
                .query(Long.class)
                .single();
    }

    public List<AdminInquiryRow> findPage(InquiryStatus status, InquiryType type, int limit, long offset) {
        return jdbcClient.sql("""
                        select i.id, i.type, i.title, i.answer is not null as answered, i.created_at, p.nickname,
                               u.deleted_at is not null as withdrawn
                        from inquiry i
                        join users u on u.user_id = i.user_id
                        left join profiles p on p.user_id = i.user_id
                        """ + FILTER + """
                        order by i.created_at desc, i.id desc
                        limit :limit offset :offset
                        """)
                .param("type", typeParam(type))
                .param("answered", answeredParam(status))
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new AdminInquiryRow(
                        rs.getLong("id"),
                        InquiryType.valueOf(rs.getString("type")),
                        rs.getString("title"),
                        rs.getBoolean("answered") ? InquiryStatus.ANSWERED : InquiryStatus.RECEIVED,
                        instant(rs, "created_at"),
                        rs.getString("nickname"),
                        rs.getBoolean("withdrawn")))
                .list();
    }

    /** 조회 URL 서명은 호출자가 이 트랜잭션이 끝난 뒤에 한다. */
    @Transactional(readOnly = true)
    public Optional<AdminInquiryDetailRow> findDetail(Long inquiryId) {
        return jdbcClient.sql("""
                        select i.id, i.user_id, i.type, i.title, i.content, i.attachment_keys, i.answer,
                               i.answered_at, i.created_at, p.nickname, u.last_login_provider,
                               u.deleted_at is not null as withdrawn
                        from inquiry i
                        join users u on u.user_id = i.user_id
                        left join profiles p on p.user_id = i.user_id
                        where i.id = :id
                        """)
                .param("id", inquiryId)
                .query((rs, rowNum) -> new AdminInquiryDetailRow(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        InquiryType.valueOf(rs.getString("type")),
                        rs.getString("title"),
                        rs.getString("content"),
                        keys(rs.getArray("attachment_keys")),
                        rs.getString("answer"),
                        instant(rs, "answered_at"),
                        instant(rs, "created_at"),
                        rs.getString("nickname"),
                        rs.getString("last_login_provider"),
                        rs.getBoolean("withdrawn")))
                .optional();
    }

    private static String typeParam(InquiryType type) {
        return type == null ? null : type.name();
    }

    private static Boolean answeredParam(InquiryStatus status) {
        return status == null ? null : status == InquiryStatus.ANSWERED;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<String> keys(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
