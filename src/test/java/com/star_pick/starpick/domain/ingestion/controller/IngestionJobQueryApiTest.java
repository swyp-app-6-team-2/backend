package com.star_pick.starpick.domain.ingestion.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class IngestionJobQueryApiTest {

    private static final Long OWNER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private IngestionJobRepository repository;
    @Autowired
    private FakeObjectStorage objectStorage;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER_ID);
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    @Test
    @DisplayName("QUEUED Job은 첫 사진 미리보기와 공개 필드만 반환한다")
    void returnsQueuedJob() throws Exception {
        String first = "ingestion-inputs/1/first.jpg";
        Long id = repository.save(IngestionJob.queueImage(OWNER_ID, List.of(first, "ingestion-inputs/1/second.png")))
                .getId();

        query(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("레시피 분석 작업을 조회했습니다."))
                .andExpect(jsonPath("$.data.inputType").value("IMAGE"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.previewImageUrl").value(FakeObjectStorage.VIEW_URL_PREFIX + first))
                .andExpect(jsonPath("$.data.result").doesNotExist())
                .andExpect(jsonPath("$.data.failureCode").doesNotExist())
                .andExpect(jsonPath("$.data.inputImageKeys").doesNotExist())
                .andExpect(jsonPath("$.data.attempt").doesNotExist());
    }

    @Test
    @DisplayName("준비된 결과를 그대로 반환하고 만료 시각이 지나면 즉시 감춘다")
    void returnsReadyResultAndHidesExpiredResult() throws Exception {
        IngestionJob ready = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/a.jpg"));
        ready.startProcessing(Instant.now());
        ready.completeWithResult(draft(), Instant.now().plusSeconds(3600));
        Long readyId = repository.save(ready).getId();

        query(readyId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESULT_READY"))
                .andExpect(jsonPath("$.data.result.title").value("감자전"))
                .andExpect(jsonPath("$.data.result.ingredients[0].ingredientId").value(31));

        IngestionJob expired = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/b.jpg"));
        expired.startProcessing(Instant.now());
        expired.completeWithResult(draft(), Instant.now().minusSeconds(1));
        Long expiredId = repository.save(expired).getId();
        query(expiredId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
    }

    @Test
    @DisplayName("실패 Job은 failureCode만 반환한다")
    void returnsFailureCode() throws Exception {
        IngestionJob failed = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/a.jpg"));
        failed.fail(IngestionFailureCode.PROCESSING_FAILED);
        Long id = repository.save(failed).getId();

        query(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
    }

    @Test
    @DisplayName("미리보기 URL 서명 실패는 Job 조회 자체를 실패시키지 않는다")
    void hidesPreviewWhenSigningFails() throws Exception {
        Long id = repository.save(IngestionJob.queueImage(
                OWNER_ID, List.of("ingestion-inputs/1/a.jpg"))).getId();
        objectStorage.startFailing();

        query(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.previewImageUrl").doesNotExist());
    }

    @Test
    @DisplayName("없거나 다른 사용자의 Job은 같은 404로 감춘다")
    void hidesMissingAndForeignJob() throws Exception {
        query(999999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_NOT_FOUND"));

        fixtures.seedUser(2L);
        Long foreignId = repository.save(IngestionJob.queueImage(2L, List.of("ingestion-inputs/2/a.jpg"))).getId();
        query(foreignId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("숫자가 아닌 ID와 인증 없는 조회를 거절한다")
    void validatesPathAndAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/ingestion-jobs/not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
        mockMvc.perform(get("/api/v1/ingestion-jobs/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("소비된 Job은 미리보기와 결과를 모두 감춘다")
    void hidesPreviewAndResultOnceConsumed() throws Exception {
        String first = "ingestion-inputs/1/consumed.jpg";
        Long id = repository.save(IngestionJob.queueImage(OWNER_ID, List.of(first))).getId();
        objectStorage.putObject(first);

        // 2단계의 Recipe 저장이 하는 일을 흉내낸다. 아직 consumedAt 을 쓰는 코드가 없다.
        jdbcTemplate.update("""
                update ingestion_job
                   set status = 'RESULT_READY', expires_at = now() + interval '1 hour', consumed_at = now()
                 where id = ?
                """, id);

        // Recipe 가 삭제되면 원본 사진도 지워지므로 서명 URL 을 내주면 깨진 이미지가 된다.
        query(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESULT_READY"))
                .andExpect(jsonPath("$.data.previewImageUrl").isEmpty())
                .andExpect(jsonPath("$.data.result").isEmpty());
    }

    private ResultActions query(Long id) throws Exception {
        return mockMvc.perform(get("/api/v1/ingestion-jobs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private RecipeDraft draft() {
        return new RecipeDraft("감자전", RecipeCategory.KOREAN, null, 2,
                List.of(new RecipeDraft.Ingredient(31L, "감자", "2개")),
                List.of(new RecipeDraft.Step("감자를 간다.")));
    }
}
