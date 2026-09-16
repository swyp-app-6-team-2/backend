package com.star_pick.starpick.domain.recipe.controller;

import com.star_pick.starpick.domain.recipe.controller.request.RecipeCreateRequest;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeRecommendationRequest;
import com.star_pick.starpick.domain.recipe.controller.response.RecipeRecommendationResponse;
import com.star_pick.starpick.domain.recipe.controller.request.RecipeUpdateRequest;
import com.star_pick.starpick.domain.recipe.controller.response.RecipeCreateResponse;
import com.star_pick.starpick.domain.recipe.controller.response.RecipeDetailResponse;
import com.star_pick.starpick.domain.recipe.controller.response.RecipeListResponse;
import com.star_pick.starpick.domain.recipe.domain.RecipeListSort;
import com.star_pick.starpick.domain.recipe.service.RecipeCreateResult;
import com.star_pick.starpick.domain.recipe.service.RecipeService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/recipes")
@Tag(name = "레시피", description = "레시피 생성·조회·수정·삭제 API")
public class RecipeController {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final RecipeService recipeService;

    @Operation(summary = "레시피 랜덤·재료 기반 추천",
            description = """
                    본인이 저장한 레시피 중 한 건을 추천합니다.
                    - RANDOM: 보유 레시피 전체 중 랜덤 한 건입니다.
                    - INGREDIENT_BASED: 서버에 등록된 내 보유 재료와 ingredientId가 하나 이상 일치하는 후보 중 랜덤 한 건입니다.
                      selectedIngredients는 받지 않으며, 마스터 ID가 없는 직접 입력 재료는 매칭하지 않습니다.
                    - previousRecipeId는 두 방식 모두 후보에서 제외합니다. 유일한 후보여도 다시 반환하지 않습니다.
                    - 후보가 없으면 200과 data: null을 반환하며, 전체 랜덤으로 자동 전환하지 않습니다.
                    - category와 mainIngredients는 추천 카드 명세의 필드명이며, 재료명은 저장된 표시 순서입니다.
                    """)
    @PostMapping("/recommendations")
    public ApiResponse<RecipeRecommendationResponse> recommendRecipe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RecipeRecommendationRequest request) {
        RecipeRecommendationResponse result = recipeService.recommend(user.userId(), request);
        return ApiResponse.ok(result == null ? "조건에 맞는 추천 레시피가 없습니다." : "레시피 추천에 성공했습니다.", result);
    }

    @Operation(summary = "레시피 생성",
            description = """
                    레시피를 저장합니다.
                    - `ingestionJobId` 가 없으면 직접 입력(MANUAL)입니다.
                    - 있으면 분석 결과로 저장합니다. 등록 방식과 출처는 분석 작업에서 정합니다.
                    - 같은 `ingestionJobId` 로 다시 요청하면 새로 만들지 않고 기존 recipeId 와 200 을 반환합니다.
                      이때 요청 내용은 반영하지 않습니다. 요청 형식 검증은 먼저 거칩니다.
                    """)
    // ResponseEntity 로 상태를 고르면 springdoc 이 200 하나만 추론하므로 두 응답을 명시한다.
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "최초 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "같은 ingestionJobId 로 이미 만든 레시피")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<RecipeCreateResponse>> createRecipe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RecipeCreateRequest request) {

        RecipeCreateResult result = recipeService.create(user.userId(), request);
        RecipeCreateResponse body = new RecipeCreateResponse(result.recipeId());
        if (!result.created()) {
            return ResponseEntity.ok(ApiResponse.ok("이미 생성된 레시피를 반환합니다.", body));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("레시피가 생성되었습니다.", body));
    }

    @Operation(summary = "레시피 목록 조회",
            description = """
                    본인이 보유한 레시피를 페이지 단위로 조회합니다.
                    - `sort` 는 `LATEST`(최신순, 기본) 또는 `OLDEST`(오래된순)입니다.
                    - `totalCount` 는 페이지 크기가 아니라 전체 결과 수입니다.
                    - 검색·필터는 제공하지 않습니다. Discovery 책임입니다.
                    """)
    @GetMapping
    public ApiResponse<RecipeListResponse> getRecipes(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "LATEST") RecipeListSort sort) {

        // Bean Validation 을 쓰지 않는 것이 의도다. @ModelAttribute + @Valid 는 BindException 을
        // 던지는데 ResponseEntityExceptionHandler 가 처리하지 않아 500 이 되고, @Validated + @Min 은
        // 400 은 나가지만 data.code 가 없어 FE 가 분기할 수 없다.
        if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }

        return ApiResponse.ok("레시피 목록을 조회했습니다.",
                recipeService.getRecipes(user.userId(), page, size, sort));
    }

    @Operation(summary = "레시피 상세 조회",
            description = "본인이 보유한 레시피만 조회합니다. 없거나 다른 사용자의 레시피는 동일하게 404 입니다.")
    @GetMapping("/{recipeId}")
    public ApiResponse<RecipeDetailResponse> getRecipe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long recipeId) {

        return ApiResponse.ok("레시피를 조회했습니다.", recipeService.getRecipe(user.userId(), recipeId));
    }

    @Operation(summary = "레시피 수정",
            description = """
                    전달한 필드만 수정합니다.
                    - 전달하지 않은 필드는 유지합니다.
                    - nullable 단일 필드에 null 을 전달하면 값을 제거합니다.
                    - ingredients, steps 는 전달하면 전체 교체이고 빈 배열이면 전체 삭제입니다.
                    - 아무 필드도 없는 빈 요청은 400 입니다.
                    """)
    @PatchMapping("/{recipeId}")
    public ApiResponse<Void> updateRecipe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long recipeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true)
            @Valid @RequestBody(required = false) RecipeUpdateRequest request) {

        // 본문이 아예 없으면 역직렬화가 일어나지 않아 Service 까지 갈 DTO 가 없다.
        // 그 외의 "필드가 하나도 없는 요청" 판단은 유스케이스 전제조건이라 Service 가 갖는다(02-1 §6).
        if (request == null) {
            throw new BusinessException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }

        recipeService.updateRecipe(user.userId(), recipeId, request);
        return ApiResponse.ok("레시피가 수정되었습니다.", null);
    }

    @Operation(summary = "레시피 삭제",
            description = """
                    레시피를 영구 삭제합니다. 복구할 수 없습니다.
                    - 재료, 조리 순서, 조리 완료 이력이 함께 삭제됩니다.
                    - 대표 이미지, 분석에 쓴 원본 사진, 조리 완료 사진도 저장소에서 삭제합니다.
                    - 저장소 삭제가 실패해도 레시피 삭제 결과와 200 응답은 유지합니다.
                    """)
    @DeleteMapping("/{recipeId}")
    public ApiResponse<Void> deleteRecipe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long recipeId) {

        recipeService.deleteRecipe(user.userId(), recipeId);
        return ApiResponse.ok("레시피가 삭제되었습니다.", null);
    }
}
