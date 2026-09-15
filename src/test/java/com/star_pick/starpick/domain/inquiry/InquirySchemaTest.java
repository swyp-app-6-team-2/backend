package com.star_pick.starpick.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code ddl-auto: validate} 가 보지 않는 nullable·CHECK·인덱스를 실제 스키마로 확인한다. */
@IntegrationTest
class InquirySchemaTest {

    private static final long USER_ID = 1L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(USER_ID);
    }

    private String nullable(String column) {
        return jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'inquiry' and column_name = ?
                """, String.class, column);
    }

    private void insert(String attachmentKeys, String answer, String answeredAt) {
        jdbcTemplate.update("""
                insert into inquiry (user_id, type, title, content, attachment_keys, answer, answered_at, created_at)
                values (?, 'BUG', '제목', '내용', cast(? as text[]), ?, cast(? as timestamptz), now())
                """, USER_ID, attachmentKeys, answer, answeredAt);
    }

    @Test
    @DisplayName("필수 컬럼은 NOT NULL 이고 답변 두 컬럼만 nullable 이다")
    void columnNullability() {
        for (String column : List.of("user_id", "type", "title", "content", "attachment_keys", "created_at")) {
            assertThat(nullable(column)).as(column).isEqualTo("NO");
        }
        assertThat(nullable("answer")).isEqualTo("YES");
        assertThat(nullable("answered_at")).isEqualTo("YES");
    }

    @Test
    @DisplayName("사진 Key 를 생략하면 빈 배열이다")
    void attachmentKeysDefaultsToEmpty() {
        jdbcTemplate.update("""
                insert into inquiry (user_id, type, title, content, created_at)
                values (?, 'BUG', '제목', '내용', now())
                """, USER_ID);

        assertThat(jdbcTemplate.queryForObject(
                "select cardinality(attachment_keys) from inquiry", Integer.class)).isZero();
    }

    @Test
    @DisplayName("사진 Key 가 6개면 ck_inquiry_attachment_keys 위반이다")
    void rejectsSixAttachments() {
        insert("{a,b,c,d,e}", null, null);

        assertThatThrownBy(() -> insert("{a,b,c,d,e,f}", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inquiry_attachment_keys");
    }

    @Test
    @DisplayName("answer 와 answered_at 은 함께 비었거나 함께 채워져야 한다")
    void answerPairConstraint() {
        insert("{}", "답변", "2026-09-15T00:00:00Z");

        assertThatThrownBy(() -> insert("{}", "답변", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inquiry_answer");
        assertThatThrownBy(() -> insert("{}", null, "2026-09-15T00:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inquiry_answer");
    }

    @Test
    @DisplayName("목록 인덱스가 (user_id, created_at, id) 순서로 있다")
    void userListIndex() {
        String definition = jdbcTemplate.queryForObject("""
                select indexdef from pg_indexes
                where tablename = 'inquiry' and indexname = 'idx_inquiry_user_id_created_at'
                """, String.class);

        assertThat(definition).contains("(user_id, created_at, id)");
    }

    @Test
    @DisplayName("upload_object 용도에 INQUIRY_ATTACHMENT 를 넣을 수 있다")
    void uploadPurposeAcceptsInquiryAttachment() {
        jdbcTemplate.update("""
                insert into upload_object (object_key, user_id, purpose)
                values ('inquiry-attachments/1/schema-test.jpg', ?, 'INQUIRY_ATTACHMENT')
                """, USER_ID);

        assertThat(jdbcTemplate.queryForObject("select count(*) from upload_object", Integer.class)).isOne();
    }
}
