package com.star_pick.starpick.domain.upload.service;

import java.time.Instant;
import java.util.Map;

/**
 * 업로드용 서명 결과. 그대로 {@code UploadUrlIssueResponse} 로 옮겨져 클라이언트에 나가므로
 * 필드 의미는 그쪽 문서를 따른다.
 */
public record SignedPutUrl(String url, Map<String, String> headers, Instant expiresAt) {
}
