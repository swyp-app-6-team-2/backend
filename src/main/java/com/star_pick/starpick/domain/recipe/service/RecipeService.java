package com.star_pick.starpick.domain.recipe.service;

import com.star_pick.starpick.domain.cooking.service.CookHistoryCleanupService;
import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeCreateRequest;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeUpdateRequest;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeIngredientRequest;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeStepRequest;
import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeIngredient;
import com.star_pick.starpick.domain.recipe.domain.RecipeStep;
import com.star_pick.starpick.domain.recipe.controller.response.RecipeDetailResponse;
import com.star_pick.starpick.domain.recipe.exception.RecipeErrorCode;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.AttachOutcome;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecipeService {

    private final RecipeRepository recipeRepository;

    private final IngredientService ingredientService;

    private final UploadService uploadService;

    private final CookHistoryCleanupService cookHistoryCleanupService;

    /**
     * 직접 입력한 Recipe 를 저장한다.
     *
     * <p>Recipe, 재료·조리 순서, 대표 이미지 연결을 하나의 트랜잭션에서 처리한다. 연결이
     * 실패하면 예외가 나가 Recipe 도 저장되지 않는다.
     */
    @Transactional
    public Long createManual(Long userId, RecipeCreateRequest request) {
        Recipe recipe = Recipe.createManual(
                userId,
                request.title(),
                request.categoryCode(),
                request.cookTimeMinutes(),
                request.servings(),
                request.memo());

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

    /** 소유한 Recipe 의 상세를 조회한다. 없거나 다른 사용자의 것이면 동일하게 404 다. */
    @Transactional(readOnly = true)
    public RecipeDetailResponse getRecipe(Long userId, Long recipeId) {
        Recipe recipe = recipeRepository.findByIdAndUserId(recipeId, userId)
                .orElseThrow(() -> new BusinessException(RecipeErrorCode.RECIPE_NOT_FOUND));

        return RecipeDetailResponse.from(recipe, uploadService.getViewUrl(userId, recipe.getCoverImageKey()));
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
     * Recipe 를 영구 삭제한다. 순서는 {@code docs/tech-specs/recipe.md} §3.4 가 정한 계약이다.
     *
     * <p>순서는 "지울 대상을 알아낸 뒤에 지운다"는 한 가지 규칙에서 나온다. 커밋 후 저장소에서
     * 지울 Key 를 Recipe 행과 CookHistory 행에서만 알 수 있어, 행을 먼저 없애면 그 Key 를 잃는다.
     *
     * <p>재료·조리 순서는 {@code cascade = ALL, orphanRemoval = true} 로 Recipe 가 소유하므로
     * 함께 지워진다. Cooking 은 도메인 경계 때문에 FK 가 없어 직접 지워야 한다.
     *
     * <p>RecipeSource 와 RecipeSourceImage 는 아직 Entity 가 없다(Ingestion 단계). 생기면 대표
     * 이미지와 같은 자리에서 원본 이미지 Key 도 함께 확보해야 한다.
     */
    @Transactional
    public void deleteRecipe(Long userId, Long recipeId) {
        Recipe recipe = recipeRepository.findByIdAndUserIdForUpdate(recipeId, userId)
                .orElseThrow(() -> new BusinessException(RecipeErrorCode.RECIPE_NOT_FOUND));

        uploadService.releaseAndDeleteFile(userId, recipe.getCoverImageKey(), UploadPurpose.RECIPE_COVER);
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
