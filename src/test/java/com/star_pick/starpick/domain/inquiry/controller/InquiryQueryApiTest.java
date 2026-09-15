package com.star_pick.starpick.domain.inquiry.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.star_pick.starpick.domain.inquiry.repository.InquiryRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /api/v1/inquiries, GET /api/v1/inquiries/{inquiryId} 통합 테스트. */
@IntegrationTest
class InquiryQueryApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private FakeObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private ResultActions getAs(String path, Object... vars) throws Exception {
        return mockMvc.perform(get(path, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private void ageOverOneYear(Long inquiryId) {
        jdbcTemplate.update(
                "update inquiry set created_at = now() - interval '1 year 1 day' where id = ?", inquiryId);
    }

    /** 사진이 붙은 문의는 연결까지 거쳐야 하므로 접수 API 로 만든다. */
    private Long createWithAttachments(String... keys) throws Exception {
        String keysJson = keys.length == 0 ? "[]" : "[\"" + String.join("\",\"", keys) + "\"]";
        String response = mockMvc.perform(post("/api/v1/inquiries")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"RECIPE","title":"분석 실패","content":"링크가 안 돼요","attachmentKeys":%s}
                                """.formatted(keysJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.inquiryId")).longValue();
    }

    @Test
    @DisplayName("내 최근 1년 문의만 최신순으로 주고 답변·사진은 목록에 없다")
    void listsOwnRecentInquiriesLatestFirst() throws Exception {
        Long older = fixtures.saveInquiry(OWNER_ID, "먼저");
        Long newer = fixtures.saveInquiry(OWNER_ID, "나중");
        Long expired = fixtures.saveInquiry(OWNER_ID, "오래됨");
        ageOverOneYear(expired);
        fixtures.saveInquiry(OTHER_ID, "남의 것");
        inquiryRepository.saveAnswer(older, "답변", Instant.now());

        getAs("/api/v1/inquiries")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("문의 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.inquiries[0].inquiryId").value(newer))
                .andExpect(jsonPath("$.data.inquiries[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.inquiries[0].content").value("나중 내용"))
                .andExpect(jsonPath("$.data.inquiries[1].inquiryId").value(older))
                .andExpect(jsonPath("$.data.inquiries[1].status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.inquiries[0].answer").doesNotExist())
                .andExpect(jsonPath("$.data.inquiries[0].attachmentImageUrls").doesNotExist());
    }

    @Test
    @DisplayName("page·size 로 나누고 totalCount 는 전체 수다")
    void paginates() throws Exception {
        fixtures.saveInquiry(OWNER_ID, "1");
        fixtures.saveInquiry(OWNER_ID, "2");
        Long third = fixtures.saveInquiry(OWNER_ID, "3");

        getAs("/api/v1/inquiries?page=0&size=1")
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.inquiries.length()").value(1))
                .andExpect(jsonPath("$.data.inquiries[0].inquiryId").value(third));
    }

    @Test
    @DisplayName("문의가 없으면 totalCount 0 과 빈 배열이다")
    void emptyList() throws Exception {
        fixtures.seedUser(OWNER_ID);

        getAs("/api/v1/inquiries")
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.inquiries").isEmpty());
    }

    @Test
    @DisplayName("page 음수·size 0·size 101 은 400 REQUEST_VALIDATION_FAILED 다")
    void rejectsInvalidPaging() throws Exception {
        for (String query : new String[] {"page=-1", "size=0", "size=101"}) {
            getAs("/api/v1/inquiries?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("답변 전 상세는 answer·answeredAt 키가 null 로 있다")
    void detailBeforeAnswer() throws Exception {
        Long inquiryId = fixtures.saveInquiry(OWNER_ID, "제목");

        getAs("/api/v1/inquiries/{id}", inquiryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("문의를 조회했습니다."))
                .andExpect(jsonPath("$.data.title").value("제목"))
                .andExpect(jsonPath("$.data.type").value("BUG"))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.attachmentImageUrls").isEmpty())
                .andExpect(jsonPath("$.data", hasKey("answer")))
                .andExpect(jsonPath("$.data.answer").value(nullValue()))
                .andExpect(jsonPath("$.data", hasKey("answeredAt")))
                .andExpect(jsonPath("$.data.answeredAt").value(nullValue()));
    }

    @Test
    @DisplayName("답변 후 상세는 ANSWERED 와 답변을 주고, 사진은 순서대로 조회 URL 이다")
    void detailWithAnswerAndAttachments() throws Exception {
        String first = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        String second = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        Long inquiryId = createWithAttachments(first, second);
        inquiryRepository.saveAnswer(inquiryId, "확인했어요", Instant.now());

        getAs("/api/v1/inquiries/{id}", inquiryId)
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer").value("확인했어요"))
                .andExpect(jsonPath("$.data.answeredAt").isNotEmpty())
                .andExpect(jsonPath("$.data.attachmentImageUrls[0]").value(FakeObjectStorage.VIEW_URL_PREFIX + first))
                .andExpect(jsonPath("$.data.attachmentImageUrls[1]").value(FakeObjectStorage.VIEW_URL_PREFIX + second));
    }

    @Test
    @DisplayName("조회 URL 서명에 실패한 사진은 배열에서 빠지고 상세는 200 이다")
    void dropsUnsignableAttachments() throws Exception {
        String key = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        Long inquiryId = createWithAttachments(key);
        objectStorage.startFailing();

        getAs("/api/v1/inquiries/{id}", inquiryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachmentImageUrls").isEmpty());
    }

    @Test
    @DisplayName("남의 문의·없는 문의·1년 지난 문의는 404, 숫자가 아닌 id 는 400 이다")
    void detailFailures() throws Exception {
        Long others = fixtures.saveInquiry(OTHER_ID, "남의 것");
        Long expired = fixtures.saveInquiry(OWNER_ID, "오래됨");
        ageOverOneYear(expired);

        for (Long id : new Long[] {others, expired, 987654321L}) {
            getAs("/api/v1/inquiries/{id}", id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.code").value("INQUIRY_NOT_FOUND"));
        }
        getAs("/api/v1/inquiries/abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }
}
