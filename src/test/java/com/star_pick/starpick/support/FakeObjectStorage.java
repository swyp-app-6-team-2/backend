package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.upload.service.ObjectStorage;
import com.star_pick.starpick.domain.upload.service.SignedPutUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 저장소. 실제 GCS 대신 메모리에서 객체 존재 여부만 흉내낸다.
 *
 * <p>{@code ./gradlew test} 가 GCP 자격증명 없이 통과해야 하므로(CLAUDE.md 검증 원칙)
 * {@code ObjectStorage} 자리에 이것을 끼운다.
 *
 * <p>업로드는 클라이언트가 저장소에 직접 하는 동작이라 테스트에서 일어나지 않는다. 그래서
 * "발급했지만 아직 올리지 않은 상태"가 기본이고, 올린 상태를 만들려면 {@link #putObject} 를
 * 명시적으로 호출한다. 연결 시 존재 확인을 검증하려면 이 구분이 필요하다.
 */
public class FakeObjectStorage implements ObjectStorage {

    private static final String UPLOAD_URL_PREFIX = "https://fake-storage.test/upload/";
    public static final String VIEW_URL_PREFIX = "https://fake-storage.test/view/";

    private final Set<String> uploaded = ConcurrentHashMap.newKeySet();

    /** 실패를 감추는 코드가 실제로 동작하는지 검증할 때 켠다. */
    private volatile boolean failing = false;

    public void putObject(String objectKey) {
        uploaded.add(objectKey);
    }

    public boolean contains(String objectKey) {
        return uploaded.contains(objectKey);
    }

    public void startFailing() {
        this.failing = true;
    }

    public void clear() {
        uploaded.clear();
        failing = false;
    }

    private void failIfConfigured() {
        if (failing) {
            throw new IllegalStateException("저장소 장애를 흉내낸 예외");
        }
    }

    @Override
    public SignedPutUrl generateUploadUrl(String objectKey, String contentType) {
        failIfConfigured();
        return new SignedPutUrl(
                UPLOAD_URL_PREFIX + objectKey,
                Map.of("Content-Type", contentType, "x-goog-if-generation-match", "0"),
                Instant.now().plus(Duration.ofMinutes(15)));
    }

    @Override
    public String generateViewUrl(String objectKey) {
        failIfConfigured();
        return VIEW_URL_PREFIX + objectKey;
    }

    @Override
    public boolean exists(String objectKey) {
        failIfConfigured();
        return uploaded.contains(objectKey);
    }

    @Override
    public void delete(Collection<String> objectKeys) {
        failIfConfigured();
        uploaded.removeAll(objectKeys);
    }
}
