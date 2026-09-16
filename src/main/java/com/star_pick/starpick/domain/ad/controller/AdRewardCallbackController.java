package com.star_pick.starpick.domain.ad.controller;

import com.star_pick.starpick.domain.ad.service.AdRewardCallbackOutcome;
import com.star_pick.starpick.domain.ad.service.AdRewardCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google AdMob SSV 콜백 수신. REWARDED_AD_SSV.md §4.5.
 *
 * <p>사용자 JWT 대신 Google 서명을 검증한다({@code SecurityConfig} 의 GET 경로 예외). 응답은 공통
 * {@code ApiResponse} 로 감싸지 않는다 — Google 이 기대하는 형식이 아니고, 예외 경로에도 이 규칙을
 * 지킨다. 그래서 {@code AdRewardCallbackService} 는 예외를 던지지 않고
 * {@link AdRewardCallbackOutcome} 만 돌려준다({@code GlobalExceptionHandler} 를 거치지 않도록).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ads/rewards")
@Tag(name = "광고 보상 콜백", description = "AdMob SSV 콜백 수신 (Google 전용, 사용자 인증 없음)")
public class AdRewardCallbackController {

    private final AdRewardCallbackService callbackService;

    @GetMapping("/callback")
    @Operation(summary = "AdMob SSV 콜백", description = "Google 서명 검증 전용 경로다. 성공·이미 처리한 거래·영구 거절 기록 완료는 "
            + "빈 body의 200, 잘못된 서명·구문은 400, 공개키 조회 등 일시 장애는 503을 반환한다.")
    public ResponseEntity<Void> callback(HttpServletRequest request) {
        AdRewardCallbackOutcome outcome = callbackService.processCallback(
                request.getQueryString(), request.getParameterMap());
        return switch (outcome) {
            case PROCESSED -> ResponseEntity.ok().build();
            case INVALID -> ResponseEntity.badRequest().build();
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }
}
