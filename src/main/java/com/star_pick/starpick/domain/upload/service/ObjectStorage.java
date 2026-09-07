package com.star_pick.starpick.domain.upload.service;

import java.util.Collection;

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

    /**
     * 지운다. 이미 없는 Key 는 무시한다. 여러 개를 넘겨도 <b>원격 호출은 한 번</b>이다.
     *
     * <p>단건 오버로드를 두지 않는다. 호출부가 지우는 개수는 열려 있고, 건별로 부르면 저장소가
     * 느릴 때 (건수 × 타임아웃)만큼 걸린다. 한 자리에서만 지우게 해두면 그 실수를 할 수 없다.
     */
    void delete(Collection<String> objectKeys);
}
