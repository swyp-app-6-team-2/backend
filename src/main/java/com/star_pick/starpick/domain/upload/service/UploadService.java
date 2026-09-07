package com.star_pick.starpick.domain.upload.service;

import com.star_pick.starpick.domain.upload.controller.response.UploadUrlIssueResponse;
import com.star_pick.starpick.domain.upload.domain.UploadObject;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.repository.UploadObjectRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 다른 도메인이 업로드 이미지를 다룰 때 사용하는 공개 진입점.
 *
 * <p>Recipe·Cooking·Ingestion 은 {@code UploadObjectRepository} 를 직접 쓰지 않고 이 클래스를
 * 호출한다(CLAUDE.md §4, 다른 도메인의 Repository 직접 참조 금지).
 *
 * <p>메서드마다 트랜잭션 유무가 다르다. 서명과 존재 확인이 원격 호출이라, 어떤 것이 트랜잭션
 * 안에 들어가는지가 설계 판단이었다. 근거는 {@code docs/tech-specs/upload.md} §3.4.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final UploadObjectRepository uploadObjectRepository;
    private final ObjectStorage objectStorage;

    /**
     * 트랜잭션을 걸지 않는다. 서명(원격 호출)을 끝낸 뒤 저장 한 번으로 끝나기 때문이다.
     * 저장이 실패하면 서명한 URL이 클라이언트에 전달되지 않으므로 저장소에는 아무 일도 일어나지
     * 않는다. 반대로 저장 후 사용자가 업로드하지 않으면 미연결 UploadObject가 남는데, 이는
     * 이미 허용하기로 한 상태다.
     */
    public UploadUrlIssueResponse issueUploadUrl(Long userId, UploadPurpose purpose, String contentType) {
        String objectKey = generateObjectKey(userId, purpose, contentType);
        SignedPutUrl signed = objectStorage.generateUploadUrl(objectKey, contentType);

        uploadObjectRepository.save(UploadObject.issue(objectKey, userId, purpose));

        return new UploadUrlIssueResponse(objectKey, signed.url(), signed.headers(), signed.expiresAt());
    }

    /**
     * 발급받은 Key를 호출 도메인의 리소스에 연결한다. <b>호출자의 트랜잭션에 참여한다.</b>
     *
     * <p>저장소에 파일이 실제로 있는지 먼저 확인한다. 순서를 뒤집으면 확인에 실패했을 때
     * {@code attachedAt} 이 이미 설정된 채로 남아, 호출부가 반드시 예외를 던져야만 정합성이
     * 유지되는 암묵적 규칙이 생긴다.
     *
     * <p>결과를 enum 으로 돌려주고 HTTP 상태나 공개 코드는 정하지 않는다. 같은 실패가 Recipe
     * 에서는 대표 이미지 오류, Cooking 에서는 사진 오류이기 때문이다.
     *
     * <p><b>저장소가 오류를 던지면 그대로 전파시킨다.</b> 파일이 없는 경우만 {@code INVALID} 이고,
     * 권한 오류·저장소 장애·타임아웃은 잡지 않는다. 이때 트랜잭션이 롤백되고 500 이 나가는데,
     * 이것이 의도한 동작이다. 서버가 확인에 실패한 것을 "이미지가 유효하지 않다"고 돌려주면
     * 사용자에게 거짓을 말하는 것이고, 저장은 실패했는데 성공으로 보이는 상태가 더 나쁘다.
     * 조회·삭제 경로가 실패를 감추고 낮추는 것과 방침이 다른 이유는, 그쪽은 실패해도 이미
     * 저장된 데이터가 남지만 여기는 저장 자체가 걸려 있기 때문이다.
     */
    @Transactional
    public AttachOutcome attach(Long userId, String objectKey, UploadPurpose purpose) {
        if (!objectStorage.exists(objectKey)) {
            return AttachOutcome.INVALID;
        }
        if (uploadObjectRepository.attachIfUnattached(objectKey, userId, purpose) == 1) {
            return AttachOutcome.ATTACHED;
        }
        return uploadObjectRepository.findById(objectKey)
                .filter(uploadObject -> uploadObject.getUserId().equals(userId)
                        && uploadObject.getPurpose() == purpose)
                .map(uploadObject -> AttachOutcome.ALREADY_ATTACHED)
                .orElse(AttachOutcome.INVALID);
    }

    /**
     * 연결을 해제하고, 커밋 이후에 저장소 파일 삭제를 한 번 시도한다.
     * <b>호출자의 트랜잭션에 참여한다.</b>
     *
     * <p>소유자와 용도를 모두 확인한다. 호출부가 이미 검증했으리라는 전제에 기대면, 다른
     * 도메인이 이 메서드를 재사용할 때 남의 것이나 다른 용도의 UploadObject를 지울 여지가 생긴다.
     *
     * <p>DB 제거와 파일 삭제를 한 메서드에 묶은 이유는 둘이 항상 같이 일어나야 하고 순서가
     * 정해져 있기 때문이다. 호출부가 조립하게 두면 "제거는 했는데 파일 삭제 예약을 빠뜨림"이
     * 도메인마다 가능한 실수로 남는다.
     *
     * <p><b>실제로 행을 지웠을 때만 파일 삭제를 예약한다.</b> 소유자·용도가 어긋나 아무것도
     * 지우지 않았는데 파일만 지우면, DB에는 살아 있는 UploadObject가 없는 파일을 가리키게 된다.
     */
    @Transactional
    public void releaseAndDeleteFile(Long userId, String objectKey, UploadPurpose purpose) {
        if (objectKey == null) {
            return;
        }
        if (uploadObjectRepository.deleteOwned(objectKey, userId, purpose) == 0) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteFromStorageBestEffort(objectKey);
            }
        });
    }

    /**
     * 트랜잭션을 걸지 않는다. DB 접근이 없다.
     *
     * <p>소유자를 확인하되 DB를 조회하지 않는다. objectKey 형식이
     * {@code {prefix}/{userId}/{UUID}.{ext}} 라 발급자를 키 자체에서 읽을 수 있기 때문이다.
     * 덕분에 이미지를 볼 때마다 조회를 한 번 더 하지 않고도, 호출부가 사용자 입력을 그대로
     * 넘겼을 때 남의 이미지 URL이 나가는 것을 막는다.
     *
     * <p>소유자가 아니거나 서명에 실패하면 예외 대신 null 을 반환한다. 이미지 한 장 때문에
     * 레시피 조회 전체를 실패시키지 않기 위해서다.
     */
    public String getViewUrl(Long userId, String objectKey) {
        if (objectKey == null) {
            return null;
        }
        if (!isIssuedTo(userId, objectKey)) {
            log.warn("소유자가 아닌 objectKey 로 조회 URL 을 요청했습니다. userId={}, objectKey={}",
                    userId, objectKey);
            return null;
        }
        try {
            return objectStorage.generateViewUrl(objectKey);
        } catch (RuntimeException e) {
            log.warn("조회 URL 서명에 실패했습니다. objectKey={}", objectKey, e);
            return null;
        }
    }

    /** {@link #generateObjectKey} 가 만든 형식에 의존한다. 형식의 소유자가 이 클래스라 경계를 넘지 않는다. */
    private boolean isIssuedTo(Long userId, String objectKey) {
        String[] segments = objectKey.split("/");
        return segments.length == 3 && segments[1].equals(String.valueOf(userId));
    }

    /**
     * 커밋 이후에만 호출되도록 {@link #releaseAndDeleteFile} 안에서만 예약한다 — 공개하면
     * "커밋 후에 부르라"는 지킬 수 없는 규약이 호출부마다 생긴다.
     *
     * <p>실패해도 예외를 던지지 않는다. 이미 커밋된 DB 변경과 성공 응답을 되돌릴 수 없고,
     * 되돌려서도 안 되기 때문이다. 남은 파일은 버려진 객체로 취급한다.
     */
    void deleteFromStorageBestEffort(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("저장소 객체 삭제에 실패했습니다. objectKey={}", objectKey, e);
        }
    }

    /**
     * {@code {용도 prefix}/{userId}/{UUID}.{확장자}}.
     *
     * <p>UUID 를 넣어 삭제한 Key가 다시 발급되지 않게 한다. prefix 상수에는 슬래시가 없으므로
     * 여기서 붙인다.
     */
    private String generateObjectKey(Long userId, UploadPurpose purpose, String contentType) {
        return "%s/%d/%s.%s".formatted(
                purpose.prefix(), userId, UUID.randomUUID(), extensionOf(contentType));
    }

    /**
     * 지원 형식은 Request DTO 의 Bean Validation 이 이미 걸렀다. 여기 default 에 도달하면
     * 검증과 이 매핑이 어긋난 것이므로 프로그래머 오류다.
     */
    private String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("지원하지 않는 contentType: " + contentType);
        };
    }
}
