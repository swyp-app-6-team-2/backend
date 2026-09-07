package com.star_pick.starpick.domain.upload.controller.response;

import java.time.Instant;
import java.util.Map;

/**
 * 업로드 URL 발급 응답.
 *
 * @param objectKey     이후 Recipe·Cooking·Ingestion API 에 전달할 안정적인 식별자
 * @param uploadUrl     저장소로 직접 PUT 할 서명된 URL
 * @param uploadHeaders PUT 요청에 그대로 포함해야 하는 헤더. 서명에 포함돼 있어 하나라도 빠지거나
 *                      값이 다르면 저장소가 서명 불일치로 거부한다
 * @param expiresAt     만료 시각. {@code Instant} 라야 ISO 8601 UTC(끝에 {@code Z})로 직렬화된다.
 *                      이 저장소의 기존 시각 필드는 {@code LocalDateTime} 이지만 그대로 쓰면
 *                      {@code Z} 가 빠져 응답 계약이 깨진다
 */
public record UploadUrlIssueResponse(
        String objectKey,
        String uploadUrl,
        Map<String, String> uploadHeaders,
        Instant expiresAt
) {
}
