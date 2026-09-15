package com.star_pick.starpick.domain.inquiry.repository;

import com.star_pick.starpick.domain.inquiry.domain.Inquiry;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** 내 문의 한 페이지. 정렬은 {@code Pageable} 이 갖고 있다. */
    Page<Inquiry> findByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant from, Pageable pageable);

    Optional<Inquiry> findByIdAndUserIdAndCreatedAtGreaterThanEqual(Long id, Long userId, Instant from);

    /** 답변을 교체하고 첫 답변 시각은 유지한다. 0 이면 없는 문의다. */
    @Transactional
    @Modifying
    @Query("update Inquiry i set i.answer = :answer, i.answeredAt = coalesce(i.answeredAt, :now) where i.id = :id")
    int saveAnswer(@Param("id") Long id, @Param("answer") String answer, @Param("now") Instant now);
}
