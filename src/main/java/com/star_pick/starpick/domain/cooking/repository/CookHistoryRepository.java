package com.star_pick.starpick.domain.cooking.repository;

import com.star_pick.starpick.domain.cooking.domain.CookHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CookHistoryRepository extends JpaRepository<CookHistory, Long> {

    /**
     * 최근 조리 순. 같은 시각이면 id 로 순서를 고정한다.
     *
     * <p>id 는 응답에 노출하지 않는 내부 보조 기준이다. 없으면 같은 시각 기록의 순서가
     * 요청마다 흔들린다.
     */
    List<CookHistory> findByRecipeIdOrderByCookedAtDescIdDesc(Long recipeId);
}
