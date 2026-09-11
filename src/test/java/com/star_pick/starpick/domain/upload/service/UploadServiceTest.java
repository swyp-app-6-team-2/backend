package com.star_pick.starpick.domain.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.star_pick.starpick.domain.upload.controller.response.UploadUrlIssueResponse;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.repository.UploadObjectRepository;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 연결·해제 규칙 검증. 다른 도메인이 의존하는 계약이라 경계 조건을 모두 확인한다. */
@IntegrationTest
class UploadServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private UploadObjectRepository uploadObjectRepository;

    @Autowired
    private FakeObjectStorage objectStorage;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        uploadObjectRepository.deleteAll();
        objectStorage.clear();
        fixtures.seedUser(OWNER_ID);
    }

    @Test
    @DisplayName("발급하면 미연결 UploadObject 가 남고 objectKey 에 용도·사용자가 들어간다")
    void issuesUrlAndSavesUnattachedObject() {
        UploadUrlIssueResponse response =
                uploadService.issueUploadUrl(OWNER_ID, UploadPurpose.RECIPE_COVER, "image/jpeg");

        assertThat(response.objectKey()).startsWith("recipe-covers/1/").endsWith(".jpg");
        assertThat(response.uploadUrl()).isNotBlank();
        assertThat(response.uploadHeaders())
                .containsEntry("Content-Type", "image/jpeg")
                .containsEntry("x-goog-if-generation-match", "0");
        assertThat(response.expiresAt()).isNotNull();

        assertThat(uploadObjectRepository.findById(response.objectKey()))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.getUserId()).isEqualTo(OWNER_ID);
                    assertThat(saved.getPurpose()).isEqualTo(UploadPurpose.RECIPE_COVER);
                    assertThat(saved.isAttached()).isFalse();
                });
    }

    @Test
    @DisplayName("형식마다 확장자가 다르다")
    void usesExtensionMatchingContentType() {
        assertThat(uploadService.issueUploadUrl(OWNER_ID, UploadPurpose.RECIPE_COVER, "image/png")
                .objectKey()).endsWith(".png");
        assertThat(uploadService.issueUploadUrl(OWNER_ID, UploadPurpose.RECIPE_COVER, "image/webp")
                .objectKey()).endsWith(".webp");
    }

    @Test
    @DisplayName("업로드까지 마친 본인 Key 는 연결된다")
    void attachesUploadedKey() {
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);

        AttachOutcome outcome =
                uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(outcome).isEqualTo(AttachOutcome.ATTACHED);
        assertThat(uploadObjectRepository.findById(objectKey))
                .get()
                .satisfies(saved -> assertThat(saved.isAttached()).isTrue());
    }

    @Test
    @DisplayName("발급만 받고 업로드하지 않은 Key 는 연결되지 않는다")
    void rejectsKeyThatWasNeverUploaded() {
        String objectKey = uploadService
                .issueUploadUrl(OWNER_ID, UploadPurpose.RECIPE_COVER, "image/jpeg")
                .objectKey();

        AttachOutcome outcome =
                uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(outcome).isEqualTo(AttachOutcome.INVALID);
    }

    @Test
    @DisplayName("남의 Key 는 연결되지 않는다")
    void rejectsOtherUsersKey() {
        String objectKey = fixtures.uploadedKey(OTHER_USER_ID, UploadPurpose.RECIPE_COVER);

        AttachOutcome outcome =
                uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(outcome).isEqualTo(AttachOutcome.INVALID);
    }

    @Test
    @DisplayName("다른 용도로 발급된 Key 는 연결되지 않는다")
    void rejectsKeyIssuedForAnotherPurpose() {
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.INGESTION_INPUT);

        AttachOutcome outcome =
                uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(outcome).isEqualTo(AttachOutcome.INVALID);
    }

    @Test
    @DisplayName("존재하지 않는 Key 는 연결되지 않는다")
    void rejectsUnknownKey() {
        AttachOutcome outcome =
                uploadService.attach(OWNER_ID, "recipe-covers/1/unknown.jpg", UploadPurpose.RECIPE_COVER);

        assertThat(outcome).isEqualTo(AttachOutcome.INVALID);
    }

    @Test
    @DisplayName("이미 연결된 Key 를 다시 연결하면 ALREADY_ATTACHED 다")
    void rejectsAlreadyAttachedKey() {
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);
        uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        AttachOutcome outcome =
                uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(outcome).isEqualTo(AttachOutcome.ALREADY_ATTACHED);
    }

    @Test
    @DisplayName("해제하면 UploadObject 가 사라진다")
    void releasesOwnKey() {
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);

        uploadService.releaseAndDeleteFile(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(uploadObjectRepository.findById(objectKey)).isEmpty();
    }

    @Test
    @DisplayName("남의 Key 는 해제되지 않는다")
    void doesNotReleaseOtherUsersKey() {
        String objectKey = fixtures.uploadedKey(OTHER_USER_ID, UploadPurpose.RECIPE_COVER);

        uploadService.releaseAndDeleteFile(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(uploadObjectRepository.findById(objectKey)).isPresent();
    }

    @Test
    @DisplayName("다른 용도의 Key 는 해제되지 않는다")
    void doesNotReleaseKeyOfAnotherPurpose() {
        // 호출 도메인이 실수로 다른 용도의 Key 를 넘겨도, 아직 참조 중인 행이 지워지면 안 된다.
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);

        uploadService.releaseAndDeleteFile(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER);

        assertThat(uploadObjectRepository.findById(objectKey)).isPresent();
    }

    @Test
    @DisplayName("없는 Key 를 해제해도 예외가 나지 않는다")
    void releaseIsIdempotent() {
        uploadService.releaseAndDeleteFile(OWNER_ID, "recipe-covers/1/unknown.jpg", UploadPurpose.RECIPE_COVER);
        uploadService.releaseAndDeleteFile(OWNER_ID, null, UploadPurpose.RECIPE_COVER);

        assertThat(uploadObjectRepository.count()).isZero();
    }

    @Test
    @DisplayName("objectKey 가 null 이면 조회 URL 도 null 이다")
    void viewUrlOfNullKeyIsNull() {
        assertThat(uploadService.getViewUrl(OWNER_ID, null)).isNull();
        assertThat(uploadService.getViewUrl(OWNER_ID, "recipe-covers/1/a.jpg"))
                .isEqualTo(FakeObjectStorage.VIEW_URL_PREFIX + "recipe-covers/1/a.jpg");
    }

    @Test
    @DisplayName("발급 대상이 아닌 사용자에게는 조회 URL 을 주지 않는다")
    void viewUrlOfAnotherUsersKeyIsNull() {
        // objectKey 에 userId 가 들어 있어 DB 조회 없이 발급 대상을 판별한다. 소비 도메인이
        // 사용자 입력을 그대로 넘겨도 남의 이미지 URL 이 나가지 않아야 한다.
        String othersKey = fixtures.uploadedKey(OTHER_USER_ID, UploadPurpose.RECIPE_COVER);

        assertThat(uploadService.getViewUrl(OWNER_ID, othersKey)).isNull();
        assertThat(uploadService.getViewUrl(OTHER_USER_ID, othersKey)).isNotNull();
    }

    @Test
    @DisplayName("형식이 어긋난 objectKey 로는 조회 URL 을 주지 않는다")
    void viewUrlOfMalformedKeyIsNull() {
        assertThat(uploadService.getViewUrl(OWNER_ID, "recipe-covers/1")).isNull();
        assertThat(uploadService.getViewUrl(OWNER_ID, "a/b/c/d.jpg")).isNull();
    }

    @Test
    @DisplayName("서명이 실패하면 UploadObject 를 남기지 않는다")
    void doesNotSaveWhenSigningFails() {
        // 서명을 먼저 하고 저장하므로, 서명이 깨지면 미연결 행이 쌓이지 않는다.
        objectStorage.startFailing();

        assertThatThrownBy(() -> uploadService
                .issueUploadUrl(OWNER_ID, UploadPurpose.RECIPE_COVER, "image/jpeg"))
                .isInstanceOf(RuntimeException.class);

        assertThat(uploadObjectRepository.count()).isZero();
    }

    @Test
    @DisplayName("조회 URL 서명이 실패하면 예외 대신 null 을 준다")
    void viewUrlDegradesToNullOnStorageFailure() {
        // 이미지 한 장 때문에 레시피 조회 전체를 실패시키지 않는다는 결정을 고정한다.
        objectStorage.startFailing();

        assertThat(uploadService.getViewUrl(OWNER_ID, "recipe-covers/1/a.jpg")).isNull();
    }

    @Test
    @DisplayName("여러 Key 를 한 번에 해제하면 소유·용도가 맞는 것만 사라진다")
    void releasesOnlyOwnedKeysInBatch() {
        String mine = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);
        String alsoMine = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);
        String othersKey = fixtures.uploadedKey(OTHER_USER_ID, UploadPurpose.RECIPE_COVER);
        String otherPurpose = fixtures.uploadedKey(OWNER_ID, UploadPurpose.COOK_HISTORY_PHOTO);

        uploadService.releaseAndDeleteFiles(
                OWNER_ID, List.of(mine, alsoMine, othersKey, otherPurpose), UploadPurpose.RECIPE_COVER);

        assertThat(uploadObjectRepository.findById(mine)).isEmpty();
        assertThat(uploadObjectRepository.findById(alsoMine)).isEmpty();
        assertThat(uploadObjectRepository.findById(othersKey)).isPresent();
        assertThat(uploadObjectRepository.findById(otherPurpose)).isPresent();

        // 실제로 지운 Key 만 저장소에서 사라진다.
        assertThat(objectStorage.contains(mine)).isFalse();
        assertThat(objectStorage.contains(alsoMine)).isFalse();
        assertThat(objectStorage.contains(othersKey)).isTrue();
        assertThat(objectStorage.contains(otherPurpose)).isTrue();
    }

    @Test
    @DisplayName("빈 목록이나 지울 것이 없는 목록을 넘겨도 안전하다")
    void batchReleaseIsSafeWhenNothingMatches() {
        String othersKey = fixtures.uploadedKey(OTHER_USER_ID, UploadPurpose.RECIPE_COVER);

        uploadService.releaseAndDeleteFiles(OWNER_ID, List.of(), UploadPurpose.RECIPE_COVER);
        uploadService.releaseAndDeleteFiles(OWNER_ID, List.of(othersKey), UploadPurpose.RECIPE_COVER);

        assertThat(objectStorage.contains(othersKey)).isTrue();
    }

    @Test
    @DisplayName("저장소 삭제가 실패해도 예외를 던지지 않는다")
    void storageDeleteSwallowsFailure() {
        // 이미 커밋된 DB 변경과 성공 응답을 되돌릴 수 없으므로 삭제 실패는 삼킨다.
        objectStorage.startFailing();

        uploadService.deleteFromStorageBestEffort(List.of("recipe-covers/1/a.jpg"));
    }

    @Test
    @DisplayName("저장소 삭제는 두 번 호출하거나 빈 목록을 넘겨도 안전하다")
    void storageDeleteIsIdempotent() {
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);

        uploadService.deleteFromStorageBestEffort(List.of(objectKey));
        uploadService.deleteFromStorageBestEffort(List.of(objectKey));
        uploadService.deleteFromStorageBestEffort(List.of());

        assertThat(objectStorage.contains(objectKey)).isFalse();
    }

    @Test
    @DisplayName("저장소 확인이 실패하면 연결은 예외를 전파한다")
    void attachPropagatesStorageFailure() {
        // 확인에 실패한 것을 "이미지가 유효하지 않다"로 돌려주면 사용자에게 거짓을 말하게 되고,
        // 저장은 실패했는데 성공으로 보이는 상태가 더 나쁘다. 그래서 여기만 실패를 감추지 않는다.
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);
        objectStorage.startFailing();

        assertThatThrownBy(() ->
                uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 저장소 파일도 UploadObject 도 그대로 남는다")
    void rollbackKeepsStorageObject() {
        // 저장소 삭제는 커밋 이후에만 실행돼야 한다. 롤백 경로에서 파일이 사라지면
        // DB 는 참조를 유지하는데 파일만 없는 상태가 된다.
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);

        transactionTemplate.executeWithoutResult(status -> {
            uploadService.releaseAndDeleteFiles(OWNER_ID, List.of(objectKey), UploadPurpose.RECIPE_COVER);
            status.setRollbackOnly();
        });

        assertThat(objectStorage.contains(objectKey)).isTrue();
        assertThat(uploadObjectRepository.findById(objectKey)).isPresent();
    }

    @Test
    @Timeout(30)
    @DisplayName("같은 Key 를 동시에 연결해도 정확히 하나만 성공한다")
    void concurrentAttachOnlyOneWins() throws Exception {
        // 조건부 UPDATE(attached_at IS NULL) 가 존재하는 이유다. SELECT 후 UPDATE 로 바꾸면
        // 두 요청이 모두 "미연결"을 보고 둘 다 성공한다.
        String objectKey = fixtures.uploadedKey(OWNER_ID, UploadPurpose.RECIPE_COVER);
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<AttachOutcome>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return transactionTemplate.execute(status ->
                            uploadService.attach(OWNER_ID, objectKey, UploadPurpose.RECIPE_COVER));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<AttachOutcome> outcomes = new ArrayList<>();
            for (Future<AttachOutcome> result : results) {
                outcomes.add(result.get(20, TimeUnit.SECONDS));
            }
            assertThat(outcomes)
                    .containsExactlyInAnyOrder(AttachOutcome.ATTACHED, AttachOutcome.ALREADY_ATTACHED);
        } finally {
            executor.shutdownNow();
        }
        assertThat(fixtures.isAttached(objectKey)).isTrue();
    }
}
