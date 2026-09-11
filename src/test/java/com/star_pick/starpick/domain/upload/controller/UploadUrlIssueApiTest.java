package com.star_pick.starpick.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.upload.repository.UploadObjectRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class UploadUrlIssueApiTest {

    private static final Long OWNER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UploadObjectRepository uploadObjectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestFixtures fixtures;

    private String accessToken;

    @BeforeEach
    void setUp() {
        uploadObjectRepository.deleteAll();
        fixtures.seedUser(OWNER_ID);
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private ResultActions issue(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/uploads/images")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("발급에 성공하면 200 과 업로드 정보를 준다")
    void issuesUploadUrl() throws Exception {
        issue("""
                {"purpose":"RECIPE_COVER","contentType":"image/jpeg"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("이미지 업로드 URL이 발급되었습니다."))
                .andExpect(jsonPath("$.data.objectKey").isString())
                .andExpect(jsonPath("$.data.uploadUrl").isString())
                .andExpect(jsonPath("$.data.expiresAt").isString())
                // 두 헤더는 서명에 포함되므로 클라이언트가 그대로 보내야 한다.
                .andExpect(jsonPath("$.data.uploadHeaders['Content-Type']").value("image/jpeg"))
                .andExpect(jsonPath("$.data.uploadHeaders['x-goog-if-generation-match']").value("0"));
    }

    @Test
    @DisplayName("발급 시각은 ISO 8601 UTC 로 나간다")
    void expiresAtIsIsoUtc() throws Exception {
        // 이 프로젝트에서 응답에 시각을 내보내는 첫 필드다. LocalDateTime 으로 만들면
        // 끝의 Z 가 빠져 공통 응답 계약이 깨지므로 형식을 고정한다.
        issue("""
                {"purpose":"RECIPE_COVER","contentType":"image/jpeg"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").value(org.hamcrest.Matchers.endsWith("Z")));
    }

    @Test
    @DisplayName("발급하면 미연결 UploadObject 가 저장된다")
    void savesUnattachedUploadObject() throws Exception {
        issue("""
                {"purpose":"COOK_HISTORY_PHOTO","contentType":"image/png"}
                """)
                .andExpect(status().isOk());

        Map<String, Object> saved = jdbcTemplate.queryForMap("select * from upload_object");
        assertThat(saved.get("user_id")).isEqualTo(OWNER_ID);
        assertThat(saved.get("purpose")).isEqualTo("COOK_HISTORY_PHOTO");
        assertThat(saved.get("attached_at")).isNull();
        assertThat((String) saved.get("object_key")).startsWith("cook-history/1/").endsWith(".png");
    }

    @Test
    @DisplayName("정의되지 않은 purpose 는 400 INVALID_REQUEST_FORMAT 이다")
    void rejectsUnknownPurpose() throws Exception {
        // enum 역직렬화 실패라 Bean Validation 이전 단계에서 걸린다.
        issue("""
                {"purpose":"PROFILE_IMAGE","contentType":"image/jpeg"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("purpose 를 보내지 않으면 400 REQUEST_VALIDATION_FAILED 이다")
    void rejectsMissingPurpose() throws Exception {
        issue("""
                {"contentType":"image/jpeg"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("purpose"));
    }

    @Test
    @DisplayName("지원하지 않는 contentType 은 400 REQUEST_VALIDATION_FAILED 이다")
    void rejectsUnsupportedContentType() throws Exception {
        issue("""
                {"purpose":"RECIPE_COVER","contentType":"image/gif"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("contentType"));
    }

    @Test
    @DisplayName("contentType 을 보내지 않아도 400 이다 — @Pattern 은 null 을 통과시킨다")
    void rejectsMissingContentType() throws Exception {
        issue("""
                {"purpose":"RECIPE_COVER"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[0].field").value("contentType"));
    }

    @Test
    @DisplayName("실패하면 UploadObject 를 만들지 않는다")
    void doesNotSaveOnFailure() throws Exception {
        issue("""
                {"purpose":"RECIPE_COVER","contentType":"image/gif"}
                """)
                .andExpect(status().isBadRequest());

        assertThat(uploadObjectRepository.count()).isZero();
    }

    @Test
    @DisplayName("인증 없이 호출하면 401 이다")
    void rejectsAnonymousRequest() throws Exception {
        mockMvc.perform(post("/api/v1/uploads/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purpose":"RECIPE_COVER","contentType":"image/jpeg"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }
}
