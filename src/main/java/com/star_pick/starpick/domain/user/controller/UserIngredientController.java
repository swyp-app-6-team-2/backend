package com.star_pick.starpick.domain.user.controller;

import com.star_pick.starpick.domain.user.dto.AddIngredientsRequest;
import com.star_pick.starpick.domain.user.dto.AddIngredientsResponse;
import com.star_pick.starpick.domain.user.service.UserIngredientService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/ingredients")
@Tag(name = "내 재료", description = "사용자 보유 재료 관리")
public class UserIngredientController {
    private final UserIngredientService ingredients;

    @PostMapping
    @Operation(summary = "재료 추가", description = "유효한 access token이 필요합니다. 활성 마스터 ID를 다건 등록하며 이미 보유한 재료는 무시합니다. 새로 추가된 재료만 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "신규 등록 목록 반환 또는 모두 보유한 경우 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청값 오류 또는 존재하지 않거나 비활성인 신규 재료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 또는 사용할 수 없는 사용자")
    })
    public ApiResponse<AddIngredientsResponse> add(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AddIngredientsRequest request) {
        var result = ingredients.add(user.userId(), request.ingredientIds());
        return ApiResponse.ok(result.ingredients().isEmpty() ? "이미 모두 등록된 재료입니다." : "재료가 등록되었습니다.", result);
    }
}
