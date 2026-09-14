package com.star_pick.starpick.domain.ingestion.service;

/**
 * {@code displayUrl} 은 이미지 카드면 그 이미지, 영상이면 썸네일이다. {@code videoUrl} 은 영상일 때만 있다.
 * 주소가 없거나 허용 규칙 밖이면 {@code null} 이다 — 쓸지 말지는 카드를 고르는 쪽이 정한다.
 */
public record InstagramMedia(boolean video, String displayUrl, String videoUrl) {
}
