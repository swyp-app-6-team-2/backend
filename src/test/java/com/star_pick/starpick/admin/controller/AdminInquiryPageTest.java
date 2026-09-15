package com.star_pick.starpick.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.star_pick.starpick.domain.inquiry.domain.Inquiry;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import com.star_pick.starpick.domain.inquiry.repository.InquiryRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 관리자 문의 목록·상세·답변 화면 통합 테스트. 인증은 {@code user()} 로 대신한다. */
@IntegrationTest
class AdminInquiryPageTest {

    // 다른 테스트가 쓰는 1·999 대신 전용 id 를 쓴다. users·profiles 는 TestFixtures.reset 이 지우지 않는 공유 행이다.
    private static final Long WRITER_ID = 4101L;
    private static final Long OTHER_ID = 4102L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InquiryRepository inquiryRepository;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(WRITER_ID);
        fixtures.seedUser(OTHER_ID);
        jdbcTemplate.update("update users set deleted_at = null, last_login_provider = 'KAKAO' where user_id in (?, ?)",
                WRITER_ID, OTHER_ID);
        jdbcTemplate.update("""
                insert into profiles (user_id, nickname, profile_image_url, created_at, updated_at)
                values (?, '별따먹는사람', 'https://example.test/p.png', now(), now())
                on conflict (user_id) do update set nickname = excluded.nickname
                """, WRITER_ID);
    }

    /** 전용 사용자라도 바꾼 상태를 남기지 않는다. */
    @AfterEach
    void restoreUsers() {
        jdbcTemplate.update("update users set deleted_at = null, last_login_provider = null where user_id in (?, ?)",
                WRITER_ID, OTHER_ID);
        jdbcTemplate.update("delete from profiles where user_id in (?, ?)", WRITER_ID, OTHER_ID);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(user("test-admin").roles("ADMIN"));
    }

    private Timestamp answeredAt(Long inquiryId) {
        return jdbcTemplate.queryForObject("select answered_at from inquiry where id = ?", Timestamp.class, inquiryId);
    }

    @Test
    @DisplayName("목록은 1년 제한 없이 최신순이고 닉네임과 상태를 보여준다")
    void listsAllInquiries() throws Exception {
        Long old = fixtures.saveInquiry(WRITER_ID, "오래된 문의");
        jdbcTemplate.update("update inquiry set created_at = now() - interval '2 years' where id = ?", old);
        fixtures.saveInquiry(OTHER_ID, "최근 문의");

        String html = mockMvc.perform(asAdmin(get("/admin/inquiries")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inquiries"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("오래된 문의", "최근 문의", "별따먹는사람", "접수완료");
        assertThat(html.indexOf("최근 문의")).isLessThan(html.indexOf("오래된 문의"));
    }

    @Test
    @DisplayName("상태·유형 필터로 거르고, 비우면 전체다")
    void filtersByStatusAndType() throws Exception {
        Long answered = fixtures.saveInquiry(WRITER_ID, "답변한 문의");
        fixtures.saveInquiry(WRITER_ID, "미답변 문의");
        inquiryRepository.save(Inquiry.create(WRITER_ID, InquiryType.SLOT, "슬롯 문의", "내용", List.of()));
        mockMvc.perform(asAdmin(post("/admin/inquiries/{id}/answer", answered)).with(csrf()).param("answer", "답변"))
                .andExpect(redirectedUrl("/admin/inquiries/" + answered));

        mockMvc.perform(asAdmin(get("/admin/inquiries").param("status", "RECEIVED")))
                .andExpect(content().string(containsString("미답변 문의")))
                .andExpect(content().string(not(containsString("답변한 문의"))));
        mockMvc.perform(asAdmin(get("/admin/inquiries").param("status", "ANSWERED")))
                .andExpect(content().string(containsString("답변한 문의")))
                .andExpect(content().string(not(containsString("미답변 문의"))));
        mockMvc.perform(asAdmin(get("/admin/inquiries").param("type", "SLOT")))
                .andExpect(content().string(containsString("슬롯 문의")))
                .andExpect(content().string(not(containsString("미답변 문의"))));
        mockMvc.perform(asAdmin(get("/admin/inquiries").param("status", "").param("type", "")))
                .andExpect(content().string(containsString("미답변 문의")))
                .andExpect(content().string(containsString("답변한 문의")))
                .andExpect(content().string(containsString("슬롯 문의")));
    }

    @Test
    @DisplayName("page 가 int 최댓값이어도 다음 페이지 링크가 생기지 않는다")
    void maxPageHasNoNextLink() throws Exception {
        fixtures.saveInquiry(WRITER_ID, "문의");

        mockMvc.perform(asAdmin(get("/admin/inquiries").param("page", String.valueOf(Integer.MAX_VALUE))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(">다음</a>"))));
    }

    @Test
    @DisplayName("없는 필터 값·음수 page·숫자가 아닌 값은 400 오류 화면이다")
    void rejectsInvalidParams() throws Exception {
        for (String[] param : new String[][] {{"status", "DONE"}, {"type", "PAYMENT"}, {"page", "-1"}, {"page", "abc"}}) {
            mockMvc.perform(asAdmin(get("/admin/inquiries").param(param[0], param[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(view().name("admin/error"))
                    .andExpect(content().string(containsString("잘못된 요청입니다.")));
        }
        mockMvc.perform(asAdmin(get("/admin/inquiries/abc")))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("admin/error"));
    }

    @Test
    @DisplayName("상세는 작성자 정보를 보여주고 사용자 입력을 이스케이프한다")
    void detailEscapesUserInput() throws Exception {
        Long inquiryId = fixtures.saveInquiry(WRITER_ID, "<script>alert(1)</script>");

        mockMvc.perform(asAdmin(get("/admin/inquiries/{id}", inquiryId)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inquiry"))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("별따먹는사람")))
                .andExpect(content().string(containsString("카카오")))
                // 답변 폼·로그아웃 폼에 CSRF 토큰이 실제로 들어가야 브라우저에서 저장할 수 있다.
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    @DisplayName("탈퇴한 작성자는 목록·상세 모두 닉네임 대신 탈퇴한 사용자로 표시한다")
    void showsWithdrawnWriter() throws Exception {
        Long inquiryId = fixtures.saveInquiry(WRITER_ID, "탈퇴자 문의");
        jdbcTemplate.update("update users set deleted_at = now() where user_id = ?", WRITER_ID);

        mockMvc.perform(asAdmin(get("/admin/inquiries")))
                .andExpect(content().string(containsString("탈퇴한 사용자")))
                .andExpect(content().string(not(containsString("별따먹는사람"))));
        mockMvc.perform(asAdmin(get("/admin/inquiries/{id}", inquiryId)))
                .andExpect(content().string(containsString("탈퇴한 사용자")));
    }

    @Test
    @DisplayName("첨부 사진은 관리자가 아니라 작성자 기준으로 서명한 조회 URL 로 보인다")
    void detailSignsAttachmentsAsWriter() throws Exception {
        String key = fixtures.uploadedKey(WRITER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        Long inquiryId = inquiryRepository.save(
                Inquiry.create(WRITER_ID, InquiryType.BUG, "사진 문의", "내용", List.of(key))).getId();

        mockMvc.perform(asAdmin(get("/admin/inquiries/{id}", inquiryId)))
                .andExpect(content().string(containsString(FakeObjectStorage.VIEW_URL_PREFIX + key)));
    }

    @Test
    @DisplayName("없는 문의 상세·답변은 404 오류 화면이다")
    void notFound() throws Exception {
        mockMvc.perform(asAdmin(get("/admin/inquiries/987654321")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("문의를 찾을 수 없습니다.")));
        mockMvc.perform(asAdmin(post("/admin/inquiries/987654321/answer")).with(csrf()).param("answer", "답변"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/error"));
    }

    @Test
    @DisplayName("답변을 저장하면 상세로 리다이렉트하고, 수정해도 첫 답변 시각은 그대로다")
    void savesAndReplacesAnswer() throws Exception {
        Long inquiryId = fixtures.saveInquiry(WRITER_ID, "문의");

        mockMvc.perform(asAdmin(post("/admin/inquiries/{id}/answer", inquiryId)).with(csrf()).param("answer", "첫 답변"))
                .andExpect(redirectedUrl("/admin/inquiries/" + inquiryId))
                .andExpect(flash().attribute("saved", true));
        Timestamp first = answeredAt(inquiryId);

        mockMvc.perform(asAdmin(post("/admin/inquiries/{id}/answer", inquiryId)).with(csrf()).param("answer", "고친 답변"))
                .andExpect(redirectedUrl("/admin/inquiries/" + inquiryId));

        assertThat(jdbcTemplate.queryForObject("select answer from inquiry where id = ?", String.class, inquiryId))
                .isEqualTo("고친 답변");
        assertThat(answeredAt(inquiryId)).isEqualTo(first);

        // 리다이렉트된 상세: 저장 문구와 기존 답변이 입력칸에 채워진다.
        mockMvc.perform(asAdmin(get("/admin/inquiries/{id}", inquiryId)).flashAttr("saved", true))
                .andExpect(content().string(containsString("답변을 저장했습니다.")))
                .andExpect(content().string(containsString("고친 답변</textarea>")));
    }

    @Test
    @DisplayName("공백·2,001자 답변은 저장하지 않고 입력을 유지한 채 오류를 보여준다")
    void rejectsInvalidAnswer() throws Exception {
        Long inquiryId = fixtures.saveInquiry(WRITER_ID, "문의");

        mockMvc.perform(asAdmin(post("/admin/inquiries/{id}/answer", inquiryId)).with(csrf()).param("answer", "   "))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inquiry"))
                .andExpect(content().string(containsString("답변을 입력해 주세요.")));
        String tooLong = "가".repeat(2001);
        mockMvc.perform(asAdmin(post("/admin/inquiries/{id}/answer", inquiryId)).with(csrf()).param("answer", tooLong))
                .andExpect(content().string(containsString("답변은 2000자를 넘을 수 없습니다.")))
                .andExpect(content().string(containsString(tooLong)));

        assertThat(jdbcTemplate.queryForObject("select answer from inquiry where id = ?", String.class, inquiryId))
                .isNull();
    }

    @Test
    @DisplayName("브라우저 textarea 의 CRLF 줄바꿈은 LF 로 저장한다")
    void normalizesCrlf() throws Exception {
        Long inquiryId = fixtures.saveInquiry(WRITER_ID, "문의");

        mockMvc.perform(asAdmin(post("/admin/inquiries/{id}/answer", inquiryId)).with(csrf())
                        .param("answer", "첫 줄\r\n둘째 줄"))
                .andExpect(redirectedUrl("/admin/inquiries/" + inquiryId));

        assertThat(jdbcTemplate.queryForObject("select answer from inquiry where id = ?", String.class, inquiryId))
                .isEqualTo("첫 줄\n둘째 줄");
    }

    @Test
    @DisplayName("로그인하지 않은 답변 저장은 로그인 화면으로 보내고 저장되지 않는다")
    void anonymousAnswerIsBlocked() throws Exception {
        Long inquiryId = fixtures.saveInquiry(WRITER_ID, "문의");

        mockMvc.perform(post("/admin/inquiries/{id}/answer", inquiryId).with(csrf()).param("answer", "답변"))
                .andExpect(redirectedUrl("/admin/login"));

        assertThat(jdbcTemplate.queryForObject("select answer from inquiry where id = ?", String.class, inquiryId))
                .isNull();
    }

    @Test
    @DisplayName("CSRF 토큰 없는 답변 저장은 로그인 화면으로 보내고 저장되지 않는다")
    void answerRequiresCsrf() throws Exception {
        Long inquiryId = fixtures.saveInquiry(WRITER_ID, "문의");

        // 세션 없는 토큰 누락은 항상 ?expired 리다이렉트다(AdminSecurityTest#logoutRequiresCsrf 참고).
        mockMvc.perform(asAdmin(post("/admin/inquiries/{id}/answer", inquiryId)).param("answer", "답변"))
                .andExpect(redirectedUrl("/admin/login?expired"));

        assertThat(jdbcTemplate.queryForObject("select answer from inquiry where id = ?", String.class, inquiryId))
                .isNull();
    }
}
