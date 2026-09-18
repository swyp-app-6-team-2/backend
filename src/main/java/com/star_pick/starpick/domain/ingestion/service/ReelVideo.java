package com.star_pick.starpick.domain.ingestion.service;

/** 보조 수집기가 찾아낸 Reel 원본. 주소는 허용 호스트를 통과한 것만 담는다. */
public record ReelVideo(String videoUrl, String caption, String thumbnailUrl) {
}
