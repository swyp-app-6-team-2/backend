package com.star_pick.starpick.domain.ad.controller;

import com.star_pick.starpick.domain.ad.dto.request.AdRewardSessionCreateRequest;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResponse;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardSessionResultResponse;
import com.star_pick.starpick.domain.ad.dto.response.AdRewardStatusResponse;
import com.star_pick.starpick.domain.ad.service.AdRewardSessionService;
import com.star_pick.starpick.domain.ad.service.AdRewardStatusService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ads/rewards")
@Tag(name = "광고 보상", description = "보상형 광고 시청 세션 발급과 레시피 저장 슬롯 지급")
public class AdRewardController {

    private final AdRewardSessionService sessions;
    private final AdRewardStatusService status;

    @GetMapping("/status")
    @Operation(summary = "광고 보상 상태 조회", description = "유효한 access token이 필요합니다. 저장 한도·잔여 슬롯·당일 지급 및 "
            + "예약 횟수와 진행 중 세션을 반환합니다. 슬롯을 지급하지 않습니다.")
    public ApiResponse<AdRewardStatusResponse> getStatus(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok("광고 보상 상태를 조회했습니다.", status.getStatus(user.userId()));
    }

    @PostMapping("/sessions")
    @Operation(summary = "시청 세션 발급", description = "유효한 access token이 필요합니다. platform과 requestId를 받아 세션을 "
            + "발급하고 해당 날짜의 보상 가능 횟수 1회를 예약합니다. 같은 requestId 로 재시도하면 기존 세션을 그대로 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "신규 발급 또는 동일 요청 재시도 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "요청값 오류 또는 아직 지원하지 않는 플랫폼"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 실패 또는 사용할 수 없는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "당일 진행 중인 세션이 있거나(AD_REWARD_SESSION_PENDING), 일일 한도를 모두 사용했거나"
                            + "(AD_REWARD_DAILY_LIMIT_REACHED), 같은 requestId 로 다른 요청을 보냈습니다(AD_REWARD_REQUEST_ID_CONFLICT)")
    })
    public ApiResponse<AdRewardSessionResponse> createSession(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AdRewardSessionCreateRequest request) {
        return ApiResponse.ok("광고 시청 세션을 발급했습니다.", sessions.createSession(user.userId(), request));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "시청 세션 결과 조회", description = "유효한 access token이 필요합니다. 본인 세션만 조회할 수 있습니다. "
            + "세션 지급 날짜가 지나도 결과는 계속 조회할 수 있습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 실패 또는 사용할 수 없는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없거나 본인 소유가 아닌 세션(AD_REWARD_SESSION_NOT_FOUND, 두 경우를 구분하지 않음)")
    })
    public ApiResponse<AdRewardSessionResultResponse> getSessionResult(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId) {
        return ApiResponse.ok("광고 시청 세션 결과를 조회했습니다.", sessions.getSessionResult(user.userId(), sessionId));
    }
}
