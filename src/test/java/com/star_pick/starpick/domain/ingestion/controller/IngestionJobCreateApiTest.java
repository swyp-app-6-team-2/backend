package com.star_pick.starpick.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
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
class IngestionJobCreateApiTest {

    private static final Long OWNER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private IngestionJobRepository repository;
    @Autowired
    private UploadService uploadService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestFixtures fixtures;

    private String accessToken;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER_ID);
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    @Test
    @DisplayName("업로드한 사진 Key를 순서대로 연결하고 QUEUED Job을 만든다")
    void createsQueuedImageJob() throws Exception {
        String first = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);
        String second = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);

        create("""
                {"inputType":"IMAGE","inputImageKeys":["%s","%s"]}
                """.formatted(first, second))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.message").value("레시피 분석 요청이 접수되었습니다."))
                .andExpect(jsonPath("$.data.ingestionJobId").isNumber());

        var saved = repository.findAll().getFirst();
        assertThat(saved.getStatus().name()).isEqualTo("QUEUED");
        assertThat(saved.getAttempt()).isZero();
        assertThat(saved.getInputImageKeys()).containsExactly(first, second);
        assertThat(fixtures.isAttached(first)).isTrue();
        assertThat(fixtures.isAttached(second)).isTrue();
    }

    @Test
    @DisplayName("URL 입력은 1단계에서 지원하지 않는다")
    void rejectsUrlInput() throws Exception {
        create("""
                {"inputType":"URL","url":"https://youtu.be/test"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INGESTION_URL_UNSUPPORTED"));
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("입력 조합과 이미지 개수 및 중복을 검증한다")
    void validatesInputShape() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"inputType\":\"IMAGE\"}",
                "{\"inputType\":\"IMAGE\",\"url\":\"https://example.com\",\"inputImageKeys\":[\"a\"]}",
                "{\"inputType\":\"IMAGE\",\"inputImageKeys\":[]}",
                "{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"\"]}",
                "{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"a\",\"a\"]}",
                "{\"inputType\":\"IMAGE\",\"inputImageKeys\":[null]}",
                "{\"inputType\":\"URL\",\"url\":\"x\",\"inputImageKeys\":[\"a\"]}")) {
            create(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        }

        String elevenKeys = java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> "\"key-" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[" + elevenKeys + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("정의되지 않은 inputType은 요청 형식 오류다")
    void rejectsUnknownInputType() throws Exception {
        create("{\"inputType\":\"VIDEO\",\"inputImageKeys\":[\"a\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("존재하지 않거나 다른 용도의 Key를 거절한다")
    void rejectsInvalidImageKey() throws Exception {
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"ingestion-inputs/1/missing.jpg\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INGESTION_INPUT_IMAGE_INVALID"));

        String cover = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(cover))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INGESTION_INPUT_IMAGE_INVALID"));

        String otherUsers = fixtures.uploadedKey(2L, UploadPurpose.INGESTION_INPUT);
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(otherUsers))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INGESTION_INPUT_IMAGE_INVALID"));

        String notUploaded = uploadService.issueUploadUrl(
                OWNER_ID, UploadPurpose.INGESTION_INPUT, "image/jpeg").objectKey();
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(notUploaded))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INGESTION_INPUT_IMAGE_INVALID"));
    }

    @Test
    @DisplayName("이미 연결한 Key는 충돌이고 중간 실패 시 앞선 연결도 롤백한다")
    void rejectsUsedKeyAndRollsBackPartialAttach() throws Exception {
        String used = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(used))
                .andExpect(status().isAccepted());
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(used))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("INGESTION_INPUT_IMAGE_ALREADY_USED"));

        String first = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);
        create("""
                {"inputType":"IMAGE","inputImageKeys":["%s","ingestion-inputs/1/missing.jpg"]}
                """.formatted(first))
                .andExpect(status().isBadRequest());
        assertThat(fixtures.isAttached(first)).isFalse();
    }

    @Test
    @DisplayName("인증하지 않은 요청은 거절한다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/ingestion-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"a\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("오늘 생성한 Job이 일일 한도에 도달하면 새 요청을 거절한다")
    void rejectsDailyLimit() throws Exception {
        saveJobs(20);
        String key = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);

        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(key))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.code").value("INGESTION_DAILY_LIMIT_EXCEEDED"));

        assertThat(repository.count()).isEqualTo(20);
        assertThat(fixtures.isAttached(key)).isFalse();
    }

    @Test
    @DisplayName("어제 생성한 Job은 오늘 일일 한도에 포함하지 않는다")
    void excludesYesterdayFromDailyLimit() throws Exception {
        saveJobs(20);
        jdbcTemplate.update("update ingestion_job set created_at = now() - interval '1 day'");
        String key = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);

        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(key))
                .andExpect(status().isAccepted());

        assertThat(repository.count()).isEqualTo(21);
    }

    @Test
    @DisplayName("Job 생성 전 거절된 요청은 일일 한도에 포함하지 않는다")
    void rejectedRequestsDoNotCountTowardDailyLimit() throws Exception {
        create("{\"inputType\":\"URL\",\"url\":\"https://youtu.be/test\"}")
                .andExpect(status().isBadRequest());
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"ingestion-inputs/1/missing.jpg\"]}")
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();

        saveJobs(19);
        String key = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);
        create("{\"inputType\":\"IMAGE\",\"inputImageKeys\":[\"%s\"]}".formatted(key))
                .andExpect(status().isAccepted());
        assertThat(repository.count()).isEqualTo(20);
    }

    private void saveJobs(int count) {
        repository.saveAll(java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> IngestionJob.queueImage(
                        OWNER_ID, List.of("ingestion-inputs/1/limit-" + index + ".jpg")))
                .toList());
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/ingestion-jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
