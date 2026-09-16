package com.star_pick.starpick.domain.ad.repository;

import com.star_pick.starpick.domain.ad.domain.AdRewardTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdRewardTransactionRepository extends JpaRepository<AdRewardTransaction, Long> {

    /**
     * 빠른 중복 확인 경로일 뿐 최종 방어는 아니다(§7). UNIQUE 충돌은 여전히 발생할 수 있으므로
     * 호출자는 저장 실패 시 이 메서드로 기존 거래인지 다시 확인한다.
     */
    boolean existsByTransactionId(String transactionId);

    Optional<AdRewardTransaction> findByTransactionId(String transactionId);
}
