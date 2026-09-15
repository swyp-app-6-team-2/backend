package com.star_pick.starpick.domain.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사용자 문의 한 건과 관리자 답변.
 *
 * <p>사용자는 접수 후 수정할 수 없다. 답변은 {@code InquiryRepository#saveAnswer} UPDATE 한 문장으로만
 * 바꾸므로 Entity 에 답변을 바꾸는 메서드를 두지 않는다.
 */
@Entity
@Getter
@Table(name = "inquiry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry {

    public static final int MAX_ATTACHMENTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private InquiryType type;

    @Column(nullable = false, updatable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false, updatable = false)
    private String content;

    /** Upload 가 발급한 저장소 객체 이름. 요청 순서를 그대로 저장한다. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false, updatable = false)
    private String[] attachmentKeys;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String answer;

    /** 처음 답변을 저장한 시각. 답변을 고쳐도 바뀌지 않는다. */
    private Instant answeredAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Inquiry(Long userId, InquiryType type, String title, String content, List<String> attachmentKeys) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.type = Objects.requireNonNull(type, "type");
        this.title = Objects.requireNonNull(title, "title");
        this.content = Objects.requireNonNull(content, "content");
        this.attachmentKeys = attachmentKeys.toArray(String[]::new);
        this.createdAt = Instant.now();
    }

    public static Inquiry create(Long userId, InquiryType type, String title, String content,
                                 List<String> attachmentKeys) {
        return new Inquiry(userId, type, title, content, attachmentKeys);
    }

    public InquiryStatus status() {
        return answer == null ? InquiryStatus.RECEIVED : InquiryStatus.ANSWERED;
    }

    public List<String> attachmentKeyList() {
        return List.of(attachmentKeys);
    }
}
