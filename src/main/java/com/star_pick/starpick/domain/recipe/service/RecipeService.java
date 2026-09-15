package com.star_pick.starpick.domain.recipe.service;

import com.star_pick.starpick.domain.cooking.service.CookHistoryCleanupService;
import com.star_pick.starpick.domain.ingestion.domain.YouTubeUrl;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobConsumeService;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobOrigin;
import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeCreateRequest;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeUpdateRequest;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeIngredientRequest;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeStepRequest;
import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeIngredient;
import com.star_pick.starpick.domain.recipe.domain.RecipeStep;
import com.star_pick.starpick.domain.recipe.controller.response.RecipeDetailResponse;
import com.star_pick.starpick.domain.recipe.controller.response.RecipeListResponse;
import com.star_pick.starpick.domain.recipe.domain.RecipeListSort;
import com.star_pick.starpick.domain.recipe.exception.RecipeErrorCode;
import com.star_pick.starpick.domain.recipe.repository.RecipeIngredientNameRow;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.AttachOutcome;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecipeService {

    private final RecipeRepository recipeRepository;

    private final IngredientService ingredientService;

    private final UploadService uploadService;

    private final CookHistoryCleanupService cookHistoryCleanupService;

    private final IngestionJobConsumeService ingestionJobConsumeService;

    /**
     * Recipe 를 저장한다. 순서는 {@code docs/specs/ingestion.md} §3.4 가 정한 계약이다.
     *
     * <p>분석 결과로 저장할 때 기존 Recipe 확인이 소비 검사보다 먼저인 이유: 응답을 못 받고 다시
     * 누른 요청이 409 가 아니라 200 을 받아야 한다. 그 경우 요청 본문은 쓰지 않는다(형식 검증은
     * 컨트롤러에서 이미 거쳤다).
     *
     * <p>같은 Job 의 동시 요청은 Job 행 잠금이 줄 세우고, {@code recipe.ingestion_job_id} UNIQUE 가
     * 마지막 방어선이다. 어느 단계에서 실패해도 소비 기록까지 함께 롤백된다.
     */
    @Transactional
    public RecipeCreateResult create(Long userId, RecipeCreateRequest request) {
        Long ingestionJobId = request.ingestionJobId();
        if (ingestionJobId == null) {
            Recipe recipe = Recipe.createManual(userId, request.title(), request.categoryCode(),
                    request.cookTimeMinutes(), request.servings(), request.memo());
            return new RecipeCreateResult(saveNew(recipe, userId, request), true);
        }

        IngestionJobOrigin origin = ingestionJobConsumeService.lockOwnedJob(userId, ingestionJobId);
        Optional<Long> existing = recipeRepository.findIdByIngestionJobId(ingestionJobId);
        if (existing.isPresent()) {
            return new RecipeCreateResult(existing.get(), false);
        }

        ingestionJobConsumeService.consume(userId, ingestionJobId);
        Recipe recipe = Recipe.createFromIngestion(userId, request.title(), request.categoryCode(),
                request.cookTimeMinutes(), request.servings(), request.memo(),
                ingestionJobId, origin.sourceUrl(), origin.sourceImageKeys(), origin.sourceThumbnailKey());
        return new RecipeCreateResult(saveNew(recipe, userId, request), true);
    }

    /** 재료·조리 순서·대표 이미지를 붙여 저장한다. 연결이 실패하면 예외가 나가 Recipe 도 저장되지 않는다. */
    private Long saveNew(Recipe recipe, Long userId, RecipeCreateRequest request) {
        recipe.replaceIngredients(toIngredients(request.ingredients()));
        recipe.replaceSteps(toSteps(request.steps()));
        changeCover(recipe, userId, request.coverImageKey());
        return recipeRepository.save(recipe).getId();
    }

    /**
     * 다른 도메인이 Recipe 존재와 소유권만 확인할 때 쓰는 경계. 없거나 다른 사용자의 것이면 동일하게 404 다.
     *
     * <p>Entity 를 반환하지 않는다. 반환하면 호출 도메인이 Recipe 내부 상태에 접근하게 되어
     * 경계가 이름만 남는다(CLAUDE.md §4). 확인 결과만 필요한 호출자를 위한 메서드다.
     */
    @Transactional(readOnly = true)
    public void requireOwnedRecipe(Long userId, Long recipeId) {
        if (!recipeRepository.existsByIdAndUserId(recipeId, userId)) {
            throw new BusinessException(RecipeErrorCode.RECIPE_NOT_FOUND);
        }
    }

    /**
     * 소유한 Recipe 를 페이지 단위로 조회한다.
     *
     * <p><b>{@code @Transactional} 을 붙이지 않는 것이 의도다.</b> 목록 조회와 재료명 조회는 각자
     * 짧은 읽기 트랜잭션에서 끝나고, 조회 URL 서명은 커넥션을 쥐지 않은 채 수행한다. 서명은 키
     * 파일 없는 ADC 구성에서 IAM 호출이 되는데(GcsConfig 주석) 저장소 클라이언트 타임아웃이
     * 걸리지 않고 <b>페이지 크기가 곧 호출 횟수</b>라 커넥션을 쥔 채로 할 일이 아니다.
     *
     * <p>트랜잭션 밖이므로 {@code recipe.getIngredients()} 를 건드리면 안 된다. open-in-view 가
     * 꺼져 있어 지연 로딩 컬렉션에 접근할 수 없다. 재료명은 별도 조회 결과만 사용한다.
     */
    public RecipeListResponse getRecipes(Long userId, int page, int size, RecipeListSort sort) {
        Page<Recipe> found = recipeRepository.findByUserId(userId, PageRequest.of(page, size, toSort(sort)));

        List<Long> recipeIds = found.getContent().stream().map(Recipe::getId).toList();
        Map<Long, List<String>> ingredientNames = findIngredientNames(userId, recipeIds);

        List<RecipeListResponse.RecipeSummary> summaries = found.getContent().stream()
                .map(recipe -> new RecipeListResponse.RecipeSummary(
                        recipe.getId(),
                        recipe.getTitle(),
                        recipe.getCategoryCode(),
                        uploadService.getViewUrl(userId, recipe.getCoverImageKey()),
                        sourceThumbnailUrl(userId, recipe),
                        ingredientNames.getOrDefault(recipe.getId(), List.of())))
                .toList();

        return new RecipeListResponse(found.getTotalElements(), summaries);
    }

    /** 빈 목록에 {@code in ()} 쿼리를 보내지 않는다. */
    private Map<Long, List<String>> findIngredientNames(Long userId, List<Long> recipeIds) {
        if (recipeIds.isEmpty()) {
            return Map.of();
        }
        return recipeRepository.findIngredientNames(userId, recipeIds).stream()
                .collect(Collectors.groupingBy(
                        RecipeIngredientNameRow::recipeId,
                        Collectors.mapping(RecipeIngredientNameRow::name, Collectors.toList())));
    }

    /**
     * {@code created_at} 만으로 정렬하면 같은 시각의 행이 페이지 경계에서 중복되거나 누락된다.
     * {@code id} 를 같은 방향의 동률 판정자로 넣어 순서를 고정한다.
     */
    private Sort toSort(RecipeListSort sort) {
        // switch 로 두는 것이 의도다. 상수를 추가하면 여기서 컴파일 에러가 난다.
        // else 로 묶으면 새 정렬 기준이 조용히 최신순으로 동작한다.
        Sort.Direction direction = switch (sort) {
            case LATEST -> Sort.Direction.DESC;
            case OLDEST -> Sort.Direction.ASC;
        };
        return Sort.by(direction, "createdAt", "id");
    }

    /** 소유한 Recipe 의 상세를 조회한다. 없거나 다른 사용자의 것이면 동일하게 404 다. */
    @Transactional(readOnly = true)
    public RecipeDetailResponse getRecipe(Long userId, Long recipeId) {
        Recipe recipe = recipeRepository.findByIdAndUserId(recipeId, userId)
                .orElseThrow(() -> new BusinessException(RecipeErrorCode.RECIPE_NOT_FOUND));

        return RecipeDetailResponse.from(recipe, uploadService.getViewUrl(userId, recipe.getCoverImageKey()),
                sourceThumbnailUrl(userId, recipe));
    }

    /**
     * 분석으로 만든 레시피의 원본 대표 이미지.
     *
     * <p>Instagram 은 Worker 가 복사해 둔 객체를, 사진 입력은 이미 보관 중인 첫 원본 사진을 서명한다. YouTube 는 영상 id 로
     * 주소가 정해져 만료되지 않으므로 계산한다. 어느 것도 없으면(직접 입력, 복사에 실패한 Instagram, 이 기능 이전
     * Instagram 레시피) null 이다. {@code YouTubeUrl} 은 의존성 없는 값 객체라 직접 쓴다.
     */
    private String sourceThumbnailUrl(Long userId, Recipe recipe) {
        if (recipe.getSourceThumbnailKey() != null) {
            return uploadService.getViewUrl(userId, recipe.getSourceThumbnailKey());
        }
        if (!recipe.getSourceImageKeys().isEmpty()) {
            return uploadService.getViewUrl(userId, recipe.getSourceImageKeys().getFirst());
        }
        if (recipe.getSourceUrl() == null) {
            return null;
        }
        return YouTubeUrl.parse(recipe.getSourceUrl()).map(YouTubeUrl::thumbnailUrl).orElse(null);
    }

    /**
     * 전달된 필드만 수정한다.
     *
     * <p>필드가 null 이면 요청에 없었다는 뜻이라 건드리지 않는다. Optional.empty() 는 명시적
     * null 이므로 값을 제거한다. 필수 필드의 orElseThrow 는 Bean Validation 이 이미 걸렀다는
     * 불변조건이고, 깨지면 서버 버그다.
     */
    @Transactional
    public void updateRecipe(Long userId, Long recipeId, RecipeUpdateRequest request) {
        if (request.hasNoChanges()) {
            throw new BusinessException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }

        Recipe recipe = recipeRepository.findByIdAndUserIdForUpdate(recipeId, userId)
                .orElseThrow(() -> new BusinessException(RecipeErrorCode.RECIPE_NOT_FOUND));

        if (request.getTitle() != null) {
            recipe.changeTitle(request.getTitle().orElseThrow());
        }
        if (request.getCategoryCode() != null) {
            recipe.changeCategory(request.getCategoryCode().orElseThrow());
        }
        if (request.getCookTimeMinutes() != null) {
            recipe.changeCookTimeMinutes(request.getCookTimeMinutes().orElse(null));
        }
        if (request.getServings() != null) {
            recipe.changeServings(request.getServings().orElseThrow());
        }
        if (request.getMemo() != null) {
            recipe.changeMemo(request.getMemo().orElse(null));
        }
        if (request.getIngredients() != null) {
            recipe.replaceIngredients(toIngredients(request.getIngredients().orElseThrow()));
        }
        if (request.getSteps() != null) {
            recipe.replaceSteps(toSteps(request.getSteps().orElseThrow()));
        }
        if (request.getCoverImageKey() != null) {
            changeCover(recipe, userId, request.getCoverImageKey().orElse(null));
        }
    }

    /**
     * Recipe 를 영구 삭제한다. 순서는 {@code docs/specs/recipe.md} §3.4 가 정한 계약이다.
     *
     * <p>순서는 "지울 대상을 알아낸 뒤에 지운다"는 한 가지 규칙에서 나온다. 커밋 후 저장소에서
     * 지울 Key 를 Recipe 행과 CookHistory 행에서만 알 수 있어, 행을 먼저 없애면 그 Key 를 잃는다.
     *
     * <p>재료·조리 순서는 {@code cascade = ALL, orphanRemoval = true} 로 Recipe 가 소유하므로
     * 함께 지워진다. Cooking 은 도메인 경계 때문에 FK 가 없어 직접 지워야 한다.
     */
    @Transactional
    public void deleteRecipe(Long userId, Long recipeId) {
        Recipe recipe = recipeRepository.findByIdAndUserIdForUpdate(recipeId, userId)
                .orElseThrow(() -> new BusinessException(RecipeErrorCode.RECIPE_NOT_FOUND));

        uploadService.releaseAndDeleteFile(userId, recipe.getCoverImageKey(), UploadPurpose.RECIPE_COVER);
        // 원본 사진의 용도는 Recipe 로 넘어온 뒤에도 INGESTION_INPUT 이다. 소비된 Job 행은 남긴다.
        uploadService.releaseAndDeleteFiles(userId, recipe.getSourceImageKeys(), UploadPurpose.INGESTION_INPUT);
        // UploadObject 가 없는 서버 저장 이미지라 해제가 아니라 파일 삭제만 예약한다. 커버와 따로 한 번 더 저장소를 부른다.
        uploadService.deleteSourceThumbnail(recipe.getSourceThumbnailKey());
        cookHistoryCleanupService.deleteByRecipe(userId, recipeId);
        recipeRepository.delete(recipe);
    }

    /**
     * 대표 이미지 연결·교체·제거. 생성과 수정이 같은 규칙을 쓴다.
     *
     * <p>저장된 값과 같은 Key 면 아무것도 하지 않는다. 수정 화면이 폼 전체를 다시 보내면서
     * 바뀌지 않은 Key 를 그대로 실어 보내는 흔한 경우인데, 그대로 연결을 요청하면 "이미
     * 연결됨"으로 판정되어 정상적인 수정이 409 로 실패한다. 생성 시에는 기존 Key 가 없으므로
     * 미전달이면 no-op, 값이 있으면 연결만 일어난다.
     */
    private void changeCover(Recipe recipe, Long userId, String newKey) {
        String currentKey = recipe.getCoverImageKey();
        if (Objects.equals(currentKey, newKey)) {
            return;
        }
        if (newKey != null) {
            attachCover(userId, newKey);
        }
        if (currentKey != null) {
            uploadService.releaseAndDeleteFile(userId, currentKey, UploadPurpose.RECIPE_COVER);
        }
        recipe.changeCoverImage(newKey);
    }

    private void attachCover(Long userId, String coverImageKey) {
        AttachOutcome outcome =
                uploadService.attach(userId, coverImageKey, UploadPurpose.RECIPE_COVER);

        switch (outcome) {
            case INVALID -> throw new BusinessException(RecipeErrorCode.RECIPE_COVER_INVALID);
            case ALREADY_ATTACHED -> throw new BusinessException(RecipeErrorCode.RECIPE_COVER_ALREADY_USED);
            case ATTACHED -> { }
        }
    }

    private List<RecipeIngredient> toIngredients(List<RecipeIngredientRequest> requests) {
        if (requests == null) {
            return List.of();
        }

        Set<Long> ingredientIds = requests.stream()
                .map(RecipeIngredientRequest::ingredientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!ingredientService.existsAll(ingredientIds)) {
            throw new BusinessException(RecipeErrorCode.RECIPE_INGREDIENT_INVALID);
        }

        return requests.stream()
                .map(request -> RecipeIngredient.of(
                        request.ingredientId(), request.name(), request.amountText()))
                .toList();
    }

    private List<RecipeStep> toSteps(List<RecipeStepRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream()
                .map(request -> RecipeStep.of(request.content()))
                .toList();
    }
}
