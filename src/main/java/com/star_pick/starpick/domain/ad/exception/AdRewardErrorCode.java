package com.star_pick.starpick.domain.ad.exception;

import com.star_pick.starpick.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Ad 도메인이 소유하는 실패. REWARDED_AD_SSV.md §4.
 *
 * <p>공개 API 는 후속 이슈에서 구현하지만, code 문자열은 이 이슈에서 확정해 FE 와 먼저 공유한다.
 * enum 상수명이 곧 공개 API 계약이다. rename 하면 FE 분기가 조용히 깨진다.
 */
@Getter
@RequiredArgsConstructor
public enum AdRewardErrorCode implements ErrorCode {

    /** 새 requestId 로 발급을 시도했으나 당일 진행 중 세션이 있다(§4.2). */
    AD_REWARD_SESSION_PENDING(HttpStatus.CONFLICT, "이미 진행 중인 시청 세션이 있습니다."),

    /** 지급·예약 횟수의 합이 일일 한도에 도달했다(§4.2). */
    AD_REWARD_DAILY_LIMIT_REACHED(HttpStatus.CONFLICT, "오늘 받을 수 있는 보상 횟수를 모두 사용했습니다."),

    /** 같은 requestId 로 이전과 다른 내용(플랫폼 등)을 요청했다(§4.2). */
    AD_REWARD_REQUEST_ID_CONFLICT(HttpStatus.CONFLICT, "동일한 요청 ID로 다른 요청을 처리할 수 없습니다."),

    /** 없거나 다른 사용자의 세션이다. 두 경우를 구분하지 않는다(§4.3, §4.4). */
    AD_REWARD_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "시청 세션을 찾을 수 없습니다."),

    /** 요청한 플랫폼에 광고 단위가 설정되지 않았다(운영 설정 미비, §9). */
    AD_REWARD_PLATFORM_UNAVAILABLE(HttpStatus.BAD_REQUEST, "아직 지원하지 않는 플랫폼입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
