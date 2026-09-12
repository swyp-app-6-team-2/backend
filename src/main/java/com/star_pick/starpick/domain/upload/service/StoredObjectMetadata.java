package com.star_pick.starpick.domain.upload.service;

/**
 * 바이트를 받지 않고 알 수 있는 저장소 객체 정보.
 *
 * <p>크기와 형식을 한 번의 원격 호출로 같이 돌려준다. 나눠서 물으면 같은 객체를 두 번 조회하게 되고,
 * 형식을 objectKey 의 확장자에서 되짚는 코드가 생긴다 — 그 확장자 규약의 소유자는 {@link UploadService}
 * 이므로 밖에서 파싱하면 경계를 넘는다.
 */
public record StoredObjectMetadata(long size, String contentType) {
}
