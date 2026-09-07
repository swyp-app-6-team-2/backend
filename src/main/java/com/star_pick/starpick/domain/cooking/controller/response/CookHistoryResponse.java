package com.star_pick.starpick.domain.cooking.controller.response;

import com.star_pick.starpick.domain.cooking.domain.CookHistory;
import java.time.Instant;

/**
 * 요리 완료 기록 1건.
 *
 * <p>식별자를 노출하지 않는다. 단건 조회·수정·삭제 API 가 없어 클라이언트가 id 로 할 수 있는
 * 일이 없다.
 *
 * <p>{@code cookedAt} 이 {@code LocalDateTime} 이 아니라 {@code Instant} 인 이유: 공통 계약이
 * ISO 8601 UTC(Z) 를 요구하는데 {@code LocalDateTime} 은 offset 없이 직렬화된다.
 *
 * <p>{@code photoUrl} 을 인자로 받는 이유: 응답 DTO 가 다른 도메인의 Service 를 호출하지 않도록
 * Service 에서 만들어 넘긴다. 서명 실패나 소유자 불일치면 null 이다.
 */
public record CookHistoryResponse(Instant cookedAt, String photoUrl, String memo) {

    public static CookHistoryResponse from(CookHistory cookHistory, String photoUrl) {
        return new CookHistoryResponse(cookHistory.getCookedAt(), photoUrl, cookHistory.getMemo());
    }
}
