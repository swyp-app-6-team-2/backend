package com.star_pick.starpick.domain.ingestion.infrastructure.apify;

/** 보조 수집 호출 자체가 실패했다. 메시지에는 예외 클래스명만 담는다. */
public class ApifyUnavailableException extends RuntimeException {

    public ApifyUnavailableException(String cause) {
        super(cause);
    }
}
