package com.star_pick.starpick.domain.ingestion.service;

/** 분석용으로 올린 영상. {@code name} 은 상태 확인·삭제에, {@code uri} 는 분석 요청에 쓴다. 로그에 남기지 않는다. */
public record UploadedVideo(String name, String uri) {
}
