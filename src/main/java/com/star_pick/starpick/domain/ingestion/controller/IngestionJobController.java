package com.star_pick.starpick.domain.ingestion.controller;

import com.star_pick.starpick.domain.ingestion.controller.request.IngestionJobCreateRequest;
import com.star_pick.starpick.domain.ingestion.controller.response.IngestionJobCreateResponse;
import com.star_pick.starpick.domain.ingestion.controller.response.IngestionJobResponse;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/ingestion-jobs")
@Tag(name = "레시피 분석", description = "사진 기반 레시피 분석 API")
public class IngestionJobController {

    private final IngestionJobService service;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "레시피 분석 요청")
    public ApiResponse<IngestionJobCreateResponse> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody IngestionJobCreateRequest request) {
        return ApiResponse.accepted(
                "레시피 분석 요청이 접수되었습니다.", service.create(user.userId(), request));
    }

    @GetMapping("/{ingestionJobId}")
    @Operation(summary = "레시피 분석 작업 조회")
    public ApiResponse<IngestionJobResponse> get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long ingestionJobId) {
        return ApiResponse.ok(
                "레시피 분석 작업을 조회했습니다.", service.getJob(user.userId(), ingestionJobId));
    }
}
