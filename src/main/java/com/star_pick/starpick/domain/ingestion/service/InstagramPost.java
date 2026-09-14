package com.star_pick.starpick.domain.ingestion.service;

import java.util.List;

/** 카드 순서대로의 미디어(1개 이상). 단일 게시물·Reel 이면 하나다. caption 이 없으면 null. */
public record InstagramPost(String caption, List<InstagramMedia> media) {
}
