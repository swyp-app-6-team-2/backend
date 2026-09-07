package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.cooking.repository.CookHistoryRepository;
import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.domain.RecipeIngredient;
import com.star_pick.starpick.domain.recipe.domain.RecipeStep;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.repository.UploadObjectRepository;
import com.star_pick.starpick.domain.upload.service.UploadService;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * 여러 통합 테스트가 함께 쓰는 데이터 픽스처.
 *
 * <p>Recipe 테스트 3개가 각자 같은 헬퍼를 들고 있었고 Cooking 테스트가 같은 것을 또 필요로 해서
 * 여기로 모았다. 한 곳에서만 쓰는 헬퍼는 옮기지 않는다 — 간접 계층만 늘어난다.
 *
 * <p>{@code @IntegrationTest} 가 직접 import 한다. <b>{@code TestcontainersConfiguration} 에 두면
 * 안 된다</b> — 이유는 그 클래스의 javadoc 에 있다.
 */
@RequiredArgsConstructor
public class TestFixtures {

    private final UploadService uploadService;

    private final FakeObjectStorage objectStorage;

    private final RecipeRepository recipeRepository;

    private final UploadObjectRepository uploadObjectRepository;

    private final CookHistoryRepository cookHistoryRepository;

    /**
     * 테스트 사이의 데이터를 비운다.
     *
     * <p>실제로 필요한 테이블만 골라 지우지 않고 항상 전부 지운다. 어떤 테스트가 어떤 테이블을
     * 남기는지는 나중에 바뀌는데, 그때 지우는 목록을 갱신하지 않으면 테스트 간 오염이 조용히 생긴다.
     */
    public void reset() {
        cookHistoryRepository.deleteAll();
        recipeRepository.deleteAll();
        uploadObjectRepository.deleteAll();
        objectStorage.clear();
    }

    /**
     * 발급부터 업로드 완료까지 마친 Key 를 만든다.
     *
     * <p>발급만 해두면 저장소에 파일이 없어 {@code attach} 가 {@code INVALID} 를 돌려준다.
     * "업로드까지 마쳤다"는 상태를 만들려면 {@code putObject} 가 반드시 필요하다.
     */
    public String uploadedKey(Long userId, UploadPurpose purpose) {
        String objectKey = uploadService.issueUploadUrl(userId, purpose, "image/jpeg").objectKey();
        objectStorage.putObject(objectKey);
        return objectKey;
    }

    /** Key 가 어딘가에 연결됐는지. 연결 성공과 롤백을 확인할 때 쓴다. */
    public boolean isAttached(String objectKey) {
        return uploadObjectRepository.findById(objectKey).orElseThrow().isAttached();
    }

    /** 재료·조리 순서가 없는 최소 Recipe. */
    public Long saveRecipe(Long ownerId) {
        return recipeRepository.save(newRecipe(ownerId)).getId();
    }

    /** 재료 2개와 조리 순서 2개를 가진 Recipe. */
    public Long saveRecipeWithChildren(Long ownerId) {
        Recipe recipe = newRecipe(ownerId);
        recipe.replaceIngredients(List.of(
                RecipeIngredient.of("김치", "1/4포기"),
                RecipeIngredient.of("두부", null)));
        recipe.replaceSteps(List.of(RecipeStep.of("물을 끓인다"), RecipeStep.of("김치를 넣는다")));
        return recipeRepository.save(recipe).getId();
    }

    private Recipe newRecipe(Long ownerId) {
        return Recipe.createManual(ownerId, "김치찌개", RecipeCategory.KOREAN, 30, 2, "조금 맵게");
    }
}
