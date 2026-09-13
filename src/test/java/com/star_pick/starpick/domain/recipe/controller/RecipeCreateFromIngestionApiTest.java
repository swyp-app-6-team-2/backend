package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.IngestionJobStatus;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.domain.RegistrationMethod;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** POST /api/v1/recipes 의 ingestionJobId 경로. MANUAL 경로는 RecipeCreateApiTest 가 본다. */
@IntegrationTest
class RecipeCreateFromIngestionApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 2L;
    private static final String YOUTUBE_URL = "https://www.youtube.com/watch?v=abc123";
    private static final RecipeDraft DRAFT =
            new RecipeDraft("김치찌개", RecipeCategory.KOREAN, null, null, List.of(), List.of());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private IngestionJobRepository jobRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER_ID);
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    private ResultActions postRecipe(String body) throws Exception {
        return postRecipe(accessToken, body);
    }

    // 이름을 post 로 두면 static import 한 MockMvcRequestBuilders.post 를 가린다.
    private ResultActions postRecipe(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions create(Long jobId) throws Exception {
        return create(jobId, "");
    }

    /** {@code extraFields} 는 앞에 쉼표를 붙여 넘긴다. 예: {@code ,"coverImageKey":"..."} */
    private ResultActions create(Long jobId, String extraFields) throws Exception {
        return postRecipe("""
                {"ingestionJobId":%d,"title":"김치찌개","categoryCode":"KOREAN",
                 "ingredients":[{"name":"김치"}],"steps":[{"content":"끓인다"}]%s}
                """.formatted(jobId, extraFields));
    }

    private void deleteRecipe(Long recipeId) throws Exception {
        mockMvc.perform(delete("/api/v1/recipes/{recipeId}", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    private static Long recipeIdOf(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.recipeId");
        return id.longValue();
    }

    private IngestionJob reload(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow();
    }

    @Test
    @DisplayName("사진 Job 으로 저장하면 201, 등록 방식 IMAGE, 사진 Key 복사, Job 소비")
    void imageJobCreatesImageRecipe() throws Exception {
        IngestionJob job = fixtures.saveReadyImageJob(OWNER_ID);

        create(job.getId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("레시피가 생성되었습니다."))
                .andExpect(jsonPath("$.data.recipeId").isNumber());

        Recipe saved = recipeRepository.findAll().getFirst();
        assertThat(saved.getRegistrationMethod()).isEqualTo(RegistrationMethod.IMAGE);
        assertThat(saved.getIngestionJobId()).isEqualTo(job.getId());
        assertThat(saved.getSourceUrl()).isNull();
        assertThat(saved.getSourceImageKeys()).containsExactlyElementsOf(job.getInputImageKeys());

        IngestionJob consumed = reload(job.getId());
        assertThat(consumed.getConsumedAt()).isNotNull();
        assertThat(consumed.getResult()).isNull();
        assertThat(consumed.getStatus()).isEqualTo(IngestionJobStatus.RESULT_READY);
    }

    @Test
    @DisplayName("URL Job 으로 저장하면 등록 방식 URL, 원본 URL 복사")
    void urlJobCreatesUrlRecipe() throws Exception {
        Long jobId = fixtures.saveReadyUrlJob(OWNER_ID, YOUTUBE_URL);

        create(jobId).andExpect(status().isCreated());

        Recipe saved = recipeRepository.findAll().getFirst();
        assertThat(saved.getRegistrationMethod()).isEqualTo(RegistrationMethod.URL);
        assertThat(saved.getSourceUrl()).isEqualTo(YOUTUBE_URL);
        assertThat(saved.getSourceImageKeys()).isEmpty();
    }

    @Test
    @DisplayName("같은 Job 으로 다시 요청하면 새로 만들지 않고 기존 recipeId 와 200")
    void retryReturnsExistingRecipe() throws Exception {
        Long jobId = fixtures.saveReadyImageJob(OWNER_ID).getId();
        Long recipeId = recipeIdOf(create(jobId).andExpect(status().isCreated()));

        create(jobId)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"status":200,"message":"이미 생성된 레시피를 반환합니다.","data":{"recipeId":%d}}
                        """.formatted(recipeId), JsonCompareMode.STRICT));

        assertThat(recipeRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("재요청이 200 으로 끝나면 본문의 대표 이미지는 연결하지 않는다")
    void retryIgnoresRequestBody() throws Exception {
        Long jobId = fixtures.saveReadyImageJob(OWNER_ID).getId();
        create(jobId).andExpect(status().isCreated());
        String coverKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);

        create(jobId, ",\"coverImageKey\":\"" + coverKey + "\"").andExpect(status().isOk());

        assertThat(fixtures.isAttached(coverKey)).isFalse();
    }

    @Test
    @DisplayName("없는 Job 과 남의 Job 은 똑같이 404 INGESTION_JOB_NOT_FOUND")
    void missingOrForeignJobIsNotFound() throws Exception {
        Long foreignJobId = fixtures.saveReadyImageJob(OTHER_ID).getId();

        create(99999999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_NOT_FOUND"));
        create(foreignJobId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_NOT_FOUND"));

        assertThat(reload(foreignJobId).getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("남의 Job 에 이미 레시피가 있어도 recipeId 를 주지 않고 404")
    void foreignJobWithRecipeIsNotFound() throws Exception {
        Long foreignJobId = fixtures.saveReadyImageJob(OTHER_ID).getId();
        postRecipe(jwtProvider.generateTokens(OTHER_ID).accessToken(), """
                {"ingestionJobId":%d,"title":"김치찌개","categoryCode":"KOREAN"}
                """.formatted(foreignJobId))
                .andExpect(status().isCreated());

        create(foreignJobId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.data.recipeId").doesNotExist());
    }

    @Test
    @DisplayName("레시피를 지운 뒤 같은 Job 으로 저장하면 결과 유효기간이 지났어도 409 INGESTION_JOB_ALREADY_CONSUMED")
    void consumedJobOfDeletedRecipeIsConflict() throws Exception {
        Long jobId = fixtures.saveReadyImageJob(OWNER_ID).getId();
        deleteRecipe(recipeIdOf(create(jobId).andExpect(status().isCreated())));

        create(jobId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_ALREADY_CONSUMED"));

        // 저장하고 며칠 뒤 지운 경우. 소비 검사가 만료 검사보다 먼저라 EXPIRED 가 아니어야 한다.
        jdbcTemplate.update("update ingestion_job set expires_at = now() - interval '1 day' where id = ?", jobId);
        create(jobId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_ALREADY_CONSUMED"));
    }

    @Test
    @DisplayName("만료 시각이 지났거나 EXPIRED 로 바뀐 Job 은 409 INGESTION_JOB_EXPIRED")
    void expiredJobIsConflict() throws Exception {
        IngestionJob pastExpiry = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/a.jpg"));
        pastExpiry.completeWithResult(DRAFT, Instant.now().minusSeconds(1));
        Long pastExpiryId = jobRepository.save(pastExpiry).getId();

        IngestionJob expired = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/b.jpg"));
        expired.completeWithResult(DRAFT, Instant.now().minusSeconds(1));
        Long expiredId = jobRepository.save(expired).getId();
        jdbcTemplate.update("update ingestion_job set status = 'EXPIRED', result = null where id = ?", expiredId);

        for (Long jobId : List.of(pastExpiryId, expiredId)) {
            create(jobId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_EXPIRED"));
        }
    }

    @Test
    @DisplayName("대기·처리 중·실패 Job 은 409 INGESTION_JOB_INVALID_STATE")
    void notReadyJobIsInvalidState() throws Exception {
        IngestionJob queued = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/a.jpg"));
        IngestionJob processing = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/b.jpg"));
        processing.startProcessing(Instant.now());
        IngestionJob failed = IngestionJob.queueImage(OWNER_ID, List.of("ingestion-inputs/1/c.jpg"));
        failed.startProcessing(Instant.now());
        failed.fail(IngestionFailureCode.PROCESSING_FAILED);

        for (IngestionJob job : jobRepository.saveAll(List.of(queued, processing, failed))) {
            create(job.getId())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data.code").value("INGESTION_JOB_INVALID_STATE"));
        }
    }

    @Test
    @DisplayName("대표 이미지가 잘못되면 400 이고 Job 소비도 롤백된다")
    void invalidCoverRollsBackConsumption() throws Exception {
        Long jobId = fixtures.saveReadyImageJob(OWNER_ID).getId();

        create(jobId, ",\"coverImageKey\":\"recipe-covers/1/missing.jpg\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("RECIPE_COVER_INVALID"));

        assertThat(reload(jobId).getConsumedAt()).isNull();
        assertThat(recipeRepository.count()).isZero();
    }

    @Test
    @DisplayName("없는 재료 id 면 400 이고 Job 소비도 롤백된다")
    void invalidIngredientRollsBackConsumption() throws Exception {
        Long jobId = fixtures.saveReadyImageJob(OWNER_ID).getId();

        postRecipe("""
                {"ingestionJobId":%d,"title":"김치찌개","categoryCode":"KOREAN",
                 "ingredients":[{"ingredientId":99999999,"name":"김치"}]}
                """.formatted(jobId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("RECIPE_INGREDIENT_INVALID"));

        assertThat(reload(jobId).getConsumedAt()).isNull();
        assertThat(recipeRepository.count()).isZero();
    }
}
