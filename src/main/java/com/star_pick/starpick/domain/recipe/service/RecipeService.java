package com.star_pick.starpick.domain.recipe.service;

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
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecipeService {

    private final RecipeRepository recipeRepository;

    /**
     * 직접 입력한 Recipe 를 저장한다.
     *
     * <p>Recipe 와 재료·조리 순서를 하나의 트랜잭션에서 처리한다. 대표 이미지 연결은
     * 이미지 업로드 단계에서 이 트랜잭션에 합류한다.
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

        return recipeRepository.save(recipe).getId();
    }

    /** 소유한 Recipe 의 상세를 조회한다. 없거나 다른 사용자의 것이면 동일하게 404 다. */
    @Transactional(readOnly = true)
    public RecipeDetailResponse getRecipe(Long userId, Long recipeId) {
        Recipe recipe = recipeRepository.findByIdAndUserId(recipeId, userId)
                .orElseThrow(() -> new BusinessException(RecipeErrorCode.RECIPE_NOT_FOUND));

        return RecipeDetailResponse.from(recipe);
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
    }

    private List<RecipeIngredient> toIngredients(List<RecipeIngredientRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream()
                .map(request -> RecipeIngredient.of(request.name(), request.amountText()))
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
