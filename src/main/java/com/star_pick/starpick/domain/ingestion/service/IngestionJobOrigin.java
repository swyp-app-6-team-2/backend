package com.star_pick.starpick.domain.ingestion.service;

import java.util.List;

/**
 * Recipe 가 출처로 복사할 값. URL 입력이면 {@code sourceUrl} 에 값이 있고 {@code sourceImageKeys} 는 빈 목록이다.
 * {@code sourceThumbnailKey} 는 Worker 가 원본 대표 이미지를 복사한 Instagram Job 에만 있다.
 */
public record IngestionJobOrigin(String sourceUrl, List<String> sourceImageKeys, String sourceThumbnailKey) {
}
