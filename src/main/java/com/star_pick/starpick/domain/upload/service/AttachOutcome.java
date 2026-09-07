package com.star_pick.starpick.domain.upload.service;

/**
 * 연결 시도의 결과.
 *
 * <p>Upload 는 HTTP 상태나 공개 오류 코드를 결정하지 않는다. 같은 실패라도 Recipe 에서는
 * 대표 이미지 오류이고 Cooking 에서는 사진 오류라, 소비 도메인이 자신의 ErrorCode 로 번역한다.
 * Upload 가 예외를 던지면 Recipe·Cooking 의 공개 계약을 알아야 해서 의존 방향이 뒤집힌다.
 */
public enum AttachOutcome {

    ATTACHED,

    /** 없거나, 다른 사용자의 것이거나, 용도가 다르거나, 저장소에 실제 파일이 없음. */
    INVALID,

    /** 이미 다른 리소스에 연결됨. */
    ALREADY_ATTACHED
}
