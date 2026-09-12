package com.star_pick.starpick.domain.ingestion.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Table(name = "ingestion_job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private IngestionSourceType sourceType;

    @Column(length = 2048, updatable = false)
    private String inputUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", updatable = false)
    private String[] inputImageKeys;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionJobStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private RecipeDraft result;

    @Enumerated(EnumType.STRING)
    private IngestionFailureCode failureCode;

    @Column(nullable = false)
    private int attempt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant startedAt;
    private Instant expiresAt;
    private Instant consumedAt;

    private IngestionJob(Long userId, List<String> inputImageKeys) {
        this.userId = userId;
        this.sourceType = IngestionSourceType.IMAGE;
        this.inputImageKeys = inputImageKeys.toArray(String[]::new);
        this.status = IngestionJobStatus.QUEUED;
    }

    public static IngestionJob queueImage(Long userId, List<String> inputImageKeys) {
        return new IngestionJob(userId, inputImageKeys);
    }

    public List<String> getInputImageKeys() {
        return inputImageKeys == null ? List.of() : List.of(inputImageKeys);
    }

    public IngestionInputType inputType() {
        return sourceType == IngestionSourceType.IMAGE ? IngestionInputType.IMAGE : IngestionInputType.URL;
    }

    public void startProcessing(Instant now) {
        status = IngestionJobStatus.PROCESSING;
        attempt++;
        startedAt = now;
    }

    public void completeWithResult(RecipeDraft draft, Instant expiresAt) {
        status = IngestionJobStatus.RESULT_READY;
        result = draft;
        failureCode = null;
        this.expiresAt = expiresAt;
    }

    public void fail(IngestionFailureCode failureCode) {
        status = IngestionJobStatus.FAILED;
        result = null;
        this.failureCode = failureCode;
    }

    public boolean isCurrentAttempt(int attempt) {
        return status == IngestionJobStatus.PROCESSING && this.attempt == attempt;
    }

    public IngestionJobStatus visibleStatus(Instant now) {
        if (status == IngestionJobStatus.RESULT_READY
                && consumedAt == null
                && expiresAt != null
                && !expiresAt.isAfter(now)) {
            return IngestionJobStatus.EXPIRED;
        }
        return status;
    }
}
