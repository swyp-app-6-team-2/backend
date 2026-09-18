package com.star_pick.starpick.domain.ingestion.service;

/**
 * 수집이 실패한 이유. 앱에 나가는 {@code IngestionFailureCode} 와 달리 로그에만 쓴다.
 * 앱에는 여러 사유가 같은 코드로 나가지만, 운영자는 이 값으로 원인을 가른다.
 */
public enum InstagramFailure {
    /** embed 조회가 로그인 페이지로 돌려보냄(3xx) 또는 429. 우리 IP 가 막힌 상태다. */
    BLOCKED,
    /**
     * embed 가 200 을 줬지만 게시물 정보(contextJSON)가 없다. 삭제·비공개·임베드 차단은 embed
     * 응답이 동일해 구분할 수 없다(2026-09-18 실측).
     */
    EMBED_NO_DATA,
    /** embed 조회가 3xx·429 말고 다른 non-200 을 줬다. */
    NOT_FOUND,
    /** Reel 인데 embed 에 영상 주소가 없다(음원 Reel). */
    NO_VIDEO,
    /** img_index 가 카드 수를 넘었다. */
    CARD_OUT_OF_RANGE,
    /**
     * 미디어 주소가 없거나 허용 호스트 밖, 지원하지 않는 형식, 빈 응답, CDN 이 non-200 을 줌
     * (서명 만료 403·404 와 CDN 차단 3xx·429 를 구분하지 않는다 — 어느 쪽이든 그 카드를 쓸 수 없다).
     */
    MEDIA_UNUSABLE,
    /** 그 밖(timeout·5xx·본문 해석 실패 등). 기본값이라 여기에 몰리면 분류를 더 나눠야 한다. */
    UNKNOWN
}
