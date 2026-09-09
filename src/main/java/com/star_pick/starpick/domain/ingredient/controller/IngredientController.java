package com.star_pick.starpick.domain.ingredient.controller;

import com.star_pick.starpick.domain.ingredient.controller.response.IngredientListResponse;
import com.star_pick.starpick.domain.ingredient.service.IngredientService;
import com.star_pick.starpick.global.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재료 마스터 조회 API.
 *
 * <p>사용자별로 다른 결과를 주지 않으므로 {@code AuthenticatedUser} 를 받지 않는다. 인증 요구는
 * {@code SecurityConfig} 의 {@code anyRequest().authenticated()} 가 강제하며
 * {@code IngredientQueryApiTest.rejectsAnonymous} 가 그것을 지킨다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ingredients")
@Tag(name = "재료", description = "재료 마스터 조회 API")
public class IngredientController {

    private final IngredientService ingredientService;

    @Operation(summary = "재료 목록 조회", description = "활성 재료 전체를 표시 순서대로 조회합니다.")
    @GetMapping
    public ApiResponse<IngredientListResponse> getIngredients() {
        return ApiResponse.ok(
                "재료 목록을 조회했습니다.",
                ingredientService.findActiveIngredients());
    }
}
