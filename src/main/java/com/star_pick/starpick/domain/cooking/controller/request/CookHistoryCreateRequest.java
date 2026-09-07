package com.star_pick.starpick.domain.cooking.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 요리 완료 기록 생성 요청.
 *
 * <p>두 필드 모두 선택이라 빈 객체 {@code {}} 도 유효하다. {@code cookedAt} 을 받지 않는 것은
 * 계약이다 — 완료 시각은 서버가 요청 처리 시점으로 기록한다.
 *
 * <p>{@code photoKey} 에 형식·길이 검증을 걸지 않는 이유는 {@code coverImageKey} 와 같다.
 * 유효성은 형식이 아니라 시스템 상태(발급 여부·소유자·용도·업로드 완료·미연결)이고 그 판단은
 * Upload 가 한다. 엉뚱하게 긴 값도 저장소 존재 확인에서 먼저 걸러진다.
 */
public record CookHistoryCreateRequest(

        @Schema(description = "업로드까지 마친 완성 사진의 objectKey", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String photoKey,

        @Schema(description = "완성 기록 메모", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String memo
) {
}
