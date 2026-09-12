package com.star_pick.starpick.domain.ingestion.repository;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, Long> {

    Optional<IngestionJob> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant createdAt);
}
