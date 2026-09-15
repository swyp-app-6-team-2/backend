package com.star_pick.starpick.domain.inquiry.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.inquiry.repository.InquiryRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** POST /api/v1/inquiries 통합 테스트. */
@IntegrationTest
class InquiryCreateApiTest {

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
        fixtures.seedUser(OWNER_ID);
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/inquiries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String body(String keysJson) {
        return """
                {"type":"SLOT","title":"슬롯이 안 늘어나요","content":"광고를 끝까지 봤어요.","attachmentKeys":%s}
                """.formatted(keysJson);
    }

    private static String json(List<String> keys) {
        return keys.stream().map(key -> "\"" + key + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    @DisplayName("사진 없이 접수하면 201 과 inquiryId 를 주고 빈 배열로 저장한다")
    void createsWithoutAttachments() throws Exception {
        create("""
                {"type":"SLOT","title":"슬롯이 안 늘어나요","content":"광고를 끝까지 봤어요."}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("문의가 접수되었습니다."))
                .andExpect(jsonPath("$.data.inquiryId").isNumber());

        assertThat(jdbcTemplate.queryForObject(
                "select cardinality(attachment_keys) from inquiry where user_id = ?", Integer.class, OWNER_ID))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select answer from inquiry", String.class)).isNull();
    }

    @Test
    @DisplayName("attachmentKeys 가 null 이면 빈 배열로 저장한다")
    void nullAttachmentKeysBecomeEmpty() throws Exception {
        create(body("null")).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "select cardinality(attachment_keys) from inquiry", Integer.class)).isZero();
    }

    @Test
    @DisplayName("사진 Key 는 요청 순서대로 저장되고 연결된다")
    void attachesInOrder() throws Exception {
        String first = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        String second = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);

        create(body(json(List.of(second, first)))).andExpect(status().isCreated());

        assertThat(inquiryRepository.findAll().getFirst().attachmentKeyList()).containsExactly(second, first);
        assertThat(fixtures.isAttached(first)).isTrue();
        assertThat(fixtures.isAttached(second)).isTrue();
    }

    @Test
    @DisplayName("없는 유형 값은 400 INVALID_REQUEST_FORMAT 이다")
    void rejectsUnknownType() throws Exception {
        create("""
                {"type":"PAYMENT","title":"제목","content":"내용"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("유형 누락·제목 공백·내용 2,001자는 400 REQUEST_VALIDATION_FAILED 와 필드 오류다")
    void rejectsInvalidFields() throws Exception {
        create("""
                {"title":"제목","content":"내용"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("type"));

        create("""
                {"type":"BUG","title":"  ","content":"내용"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[0].field").value("title"));

        create("""
                {"type":"BUG","title":"제목","content":"%s"}
                """.formatted("가".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[0].field").value("content"));

        assertThat(inquiryRepository.count()).isZero();
    }

    @Test
    @DisplayName("사진 Key 6개는 attachmentKeys 필드 오류다")
    void rejectsSixAttachments() throws Exception {
        List<String> keys = IntStream.range(0, 6).mapToObj(i -> "inquiry-attachments/1/k" + i + ".jpg").toList();

        create(body(json(keys)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("attachmentKeys"));
    }

    @Test
    @DisplayName("같은 사진 Key 중복은 attachmentKeysUnique 필드 오류다")
    void rejectsDuplicateKeys() throws Exception {
        String key = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);

        create(body(json(List.of(key, key))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("attachmentKeysUnique"));

        assertThat(fixtures.isAttached(key)).isFalse();
    }

    @Test
    @DisplayName("업로드하지 않은 Key·남의 Key·다른 용도 Key 는 400 INQUIRY_ATTACHMENT_INVALID 다")
    void rejectsInvalidKeys() throws Exception {
        String notUploaded = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        objectStorage.clear();
        String others = fixtures.uploadedKey(OTHER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        String otherPurpose = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);

        for (String key : List.of(notUploaded, others, otherPurpose)) {
            create(body(json(List.of(key))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("INQUIRY_ATTACHMENT_INVALID"));
        }
        assertThat(inquiryRepository.count()).isZero();
    }

    @Test
    @DisplayName("이미 연결된 Key 는 409 이고 앞서 연결한 Key 도 롤백된다")
    void rejectsAlreadyUsedKeyAndRollsBack() throws Exception {
        String used = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);
        create(body(json(List.of(used)))).andExpect(status().isCreated());
        String fresh = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INQUIRY_ATTACHMENT);

        create(body(json(List.of(fresh, used))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("INQUIRY_ATTACHMENT_ALREADY_USED"));

        assertThat(inquiryRepository.count()).isOne();
        assertThat(fixtures.isAttached(fresh)).isFalse();
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("[]")))
                .andExpect(status().isUnauthorized());
    }
}
