package com.star_pick.starpick.domain.upload.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 서버가 발급한 저장소 객체 하나의 소유·용도·연결 상태.
 *
 * <p>이미지 바이너리는 클라이언트가 저장소에 직접 올리므로 서버는 업로드 순간을 보지 못한다.
 * 이 Entity 가 "누구에게 어떤 용도로 발급했고 아직 어디에도 붙지 않았는지"를 대신 기억한다.
 *
 * <p>소유자는 {@code userId} 스칼라로만 갖는다. 다른 도메인의 JPA Entity 를 직접 참조하지
 * 않는다는 규칙(CLAUDE.md §4) 때문이며, 그 결과 DB 에 user FK 가 없다. {@code Recipe} 와 같다.
 *
 * <p>연결({@code attachedAt} 설정)은 이 클래스가 아니라 Repository 의 조건부 UPDATE 가 수행한다.
 * "미연결일 때만 연결"을 한 문장으로 처리해야 동시 요청에서도 한 번만 연결되기 때문이다.
 */
@Entity
@Getter
@Table(name = "upload_object")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadObject implements Persistable<String> {

    @Id
    private String objectKey;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private UploadPurpose purpose;

    /** null 이면 미연결이다. 연결 이후 다시 null 로 되돌리지 않는다. */
    private LocalDateTime attachedAt;

    private UploadObject(String objectKey, Long userId, UploadPurpose purpose) {
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
    }

    public static UploadObject issue(String objectKey, Long userId, UploadPurpose purpose) {
        return new UploadObject(objectKey, userId, purpose);
    }

    public boolean isAttached() {
        return attachedAt != null;
    }

    /**
     * PK 를 서버가 직접 만들기 때문에 신규 여부를 따로 알려준다.
     *
     * <p>이것이 없으면 Spring Data 가 "id 가 이미 있으니 기존 행"으로 판단해 {@code persist}
     * 대신 {@code merge} 를 부르고, 방금 만든 UUID Key 라 결과가 없는 것이 확정적인 SELECT 가
     * 발급 요청마다 한 번씩 헛돈다(실측 확인).
     */
    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return objectKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
