package com.star_pick.starpick.domain.upload.infrastructure;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.star_pick.starpick.domain.upload.service.ObjectStorage;
import com.star_pick.starpick.domain.upload.service.SignedPutUrl;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link ObjectStorage} 의 Google Cloud Storage 구현.
 *
 * <p>Bucket 이름, 서명 옵션, SDK 타입은 이 클래스 밖으로 노출하지 않는다.
 *
 * <p>{@code @Component} 를 붙이지 않는다. 컴포넌트 스캔에 잡히면 {@link GcsConfig} 의 조건과
 * 무관하게 살아나 테스트에서 설정값을 찾지 못하고 실패한다. Bean 등록은 {@link GcsConfig} 가 한다.
 */
class GcsObjectStorageAdapter implements ObjectStorage {

    /** 대상 위치에 객체가 없을 때만 업로드를 허용하는 조건. 덮어쓰기를 막는다. */
    private static final String GENERATION_MATCH_HEADER = "x-goog-if-generation-match";
    private static final String ONLY_IF_ABSENT = "0";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    private final Storage storage;
    private final String bucket;
    private final Duration uploadUrlExpiration;
    private final Duration viewUrlExpiration;

    GcsObjectStorageAdapter(Storage storage, String bucket,
                            Duration uploadUrlExpiration, Duration viewUrlExpiration) {
        this.storage = storage;
        this.bucket = bucket;
        this.uploadUrlExpiration = uploadUrlExpiration;
        this.viewUrlExpiration = viewUrlExpiration;
    }

    /**
     * 두 헤더를 {@code withExtHeaders} 로 서명에 포함시킨다. V4 서명은 여기 넣은 헤더를
     * canonical request 에 포함하므로, 클라이언트가 같은 헤더를 보내야만 통과한다. 응답의
     * {@code headers} 를 그대로 돌려주는 이유다.
     */
    @Override
    public SignedPutUrl generateUploadUrl(String objectKey, String contentType) {
        Map<String, String> headers = Map.of(
                CONTENT_TYPE_HEADER, contentType,
                GENERATION_MATCH_HEADER, ONLY_IF_ABSENT);

        URL url = storage.signUrl(blobInfo(objectKey),
                uploadUrlExpiration.toMinutes(), TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.withExtHeaders(headers));

        return new SignedPutUrl(url.toString(), headers, Instant.now().plus(uploadUrlExpiration));
    }

    @Override
    public String generateViewUrl(String objectKey) {
        URL url = storage.signUrl(blobInfo(objectKey),
                viewUrlExpiration.toMinutes(), TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature());

        return url.toString();
    }

    /** 객체가 없으면 {@code get} 이 null 을 반환한다. */
    @Override
    public boolean exists(String objectKey) {
        return storage.get(BlobId.of(bucket, objectKey)) != null;
    }

    @Override
    public void delete(String objectKey) {
        storage.delete(BlobId.of(bucket, objectKey));
    }

    private BlobInfo blobInfo(String objectKey) {
        return BlobInfo.newBuilder(BlobId.of(bucket, objectKey)).build();
    }
}
