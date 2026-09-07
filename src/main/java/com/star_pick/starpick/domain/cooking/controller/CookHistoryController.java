package com.star_pick.starpick.domain.cooking.controller;

import com.star_pick.starpick.domain.cooking.controller.request.CookHistoryCreateRequest;
import com.star_pick.starpick.domain.cooking.controller.response.CookHistoryResponse;
import com.star_pick.starpick.domain.cooking.service.CookHistoryService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/recipes/{recipeId}/cook-histories")
@Tag(name = "요리 완료 기록", description = "레시피별 조리 완료 이력 생성·조회 API")
public class CookHistoryController {

    private final CookHistoryService cookHistoryService;

    @Operation(summary = "요리 완료 기록 생성",
            description = """
                    조리 완료 시각은 서버가 기록합니다. 완성 사진과 메모는 모두 선택이라 빈 객체도 유효합니다.
                    생성된 기록의 식별자는 반환하지 않습니다.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> createCookHistory(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long recipeId,
            @Valid @RequestBody CookHistoryCreateRequest request) {

        cookHistoryService.create(user.userId(), recipeId, request);
        return ApiResponse.created("요리 완료 기록이 생성되었습니다.", null);
    }

    @Operation(summary = "레시피별 요리 완료 이력 조회",
            description = "최근 조리 순으로 전체 이력을 반환합니다. 페이지네이션은 없으며 이력이 없으면 빈 배열입니다.")
    @GetMapping
    public ApiResponse<List<CookHistoryResponse>> getCookHistories(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long recipeId) {

        return ApiResponse.ok("요리 완료 기록을 조회했습니다.",
                cookHistoryService.getCookHistories(user.userId(), recipeId));
    }
}
