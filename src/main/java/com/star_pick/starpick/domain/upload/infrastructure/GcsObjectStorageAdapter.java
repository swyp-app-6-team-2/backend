package com.star_pick.starpick.domain.upload.infrastructure;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.star_pick.starpick.domain.upload.service.ObjectStorage;
import com.star_pick.starpick.domain.upload.service.SignedPutUrl;
import com.star_pick.starpick.domain.upload.service.StoredObjectMetadata;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
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

    /**
     * {@link #read} 전용 클라이언트. 이유는 {@link GcsConfig#downloadStorage} 에 있다.
     *
     * <p>바이너리 본문을 받는 호출만 이것을 쓴다. 나머지는 {@link #storage} 를 쓴다.
     */
    private final Storage downloadStorage;

    private final String bucket;
    private final Duration uploadUrlExpiration;
    private final Duration viewUrlExpiration;

    GcsObjectStorageAdapter(Storage storage, Storage downloadStorage, String bucket,
                            Duration uploadUrlExpiration, Duration viewUrlExpiration) {
        this.storage = storage;
        this.downloadStorage = downloadStorage;
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

    /** {@code contentType} 은 업로드 시 서명에 포함된 헤더라, 발급 때 검증된 형식이 그대로 들어 있다. */
    @Override
    public StoredObjectMetadata metadata(String objectKey) {
        Blob blob = storage.get(BlobId.of(bucket, objectKey));
        return blob == null ? null : new StoredObjectMetadata(blob.getSize(), blob.getContentType());
    }

    /**
     * 객체 바이트를 읽는다. <b>{@link #downloadStorage} 로만</b> 부른다.
     *
     * <p>본문을 기본 클라이언트로 받으면 {@link GcsConfig} 의 5초 총 timeout 안에 최대 14MB 를
     * 받아야 해서(약 22Mbit/s 지속 필요) 느린 회선에서 {@code StorageException} 이 난다. 그
     * 예외는 Ingestion 의 재시도 분류에 걸리지 않아 재시도 없이 실패로 끝난다.
     *
     * <p>존재 확인을 위한 {@code get} 을 따로 하지 않는다. 호출부가 {@link #metadata} 로 이미
     * 확인했고, 여기서 한 번 더 물으면 사진 한 장당 원격 호출이 세 번이 된다.
     */
    @Override
    public byte[] read(String objectKey) {
        try {
            return downloadStorage.readAllBytes(BlobId.of(bucket, objectKey));
        } catch (StorageException e) {
            if (e.getCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** SDK 가 batch 요청 하나로 보낸다. 건별 성공 여부는 쓰지 않는다 — 이미 없는 것도 성공이다. */
    @Override
    public void delete(Collection<String> objectKeys) {
        if (objectKeys.isEmpty()) {
            return;
        }
        storage.delete(objectKeys.stream().map(key -> BlobId.of(bucket, key)).toList());
    }

    private BlobInfo blobInfo(String objectKey) {
        return BlobInfo.newBuilder(BlobId.of(bucket, objectKey)).build();
    }
}
