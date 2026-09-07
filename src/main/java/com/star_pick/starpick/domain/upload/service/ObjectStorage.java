package com.star_pick.starpick.domain.upload.service;

/**
 * 저장소가 해줘야 하는 일. 구현은 infrastructure 가 담당한다.
 *
 * <p>인터페이스를 두는 이유는 추상화 자체가 목적이 아니라 테스트 경계가 필요하기 때문이다.
 * {@code ./gradlew test} 는 GCP 자격증명 없이 통과해야 하므로(CLAUDE.md 검증 원칙)
 * 테스트에서 이 자리에 가짜 구현을 끼운다.
 *
 * <p>구현체는 SDK 타입과 Bucket·서명 세부사항을 이 경계 밖으로 노출하지 않는다.
 */
public interface ObjectStorage {

    /** 서명은 원격 호출이다. 키 파일이 없어 IAM 에 위임한다. */
    SignedPutUrl generateUploadUrl(String objectKey, String contentType);

    /** 서명은 원격 호출이다. */
    String generateViewUrl(String objectKey);

    /** 발급만 받고 실제로 올리지 않은 Key 를 걸러내는 데 쓴다. */
    boolean exists(String objectKey);

    /** 이미 없으면 아무 일도 일어나지 않는다. */
    void delete(String objectKey);
}
