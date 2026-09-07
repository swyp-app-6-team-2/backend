package com.star_pick.starpick.domain.cooking.repository;

import com.star_pick.starpick.domain.cooking.domain.CookHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CookHistoryRepository extends JpaRepository<CookHistory, Long> {

    /**
     * 최근 조리 순. 같은 시각이면 id 로 순서를 고정한다.
     *
     * <p>id 는 응답에 노출하지 않는 내부 보조 기준이다. 없으면 같은 시각 기록의 순서가
     * 요청마다 흔들린다.
     */
    List<CookHistory> findByRecipeIdOrderByCookedAtDescIdDesc(Long recipeId);

    /**
     * Recipe 삭제 정리용. 커밋 후 저장소에서 지울 Key 만 가져온다.
     *
     * <p>엔티티를 부르지 않는 이유는 {@code memo} 가 길이 제한 없는 text 라서다. 사진 Key 하나
     * 때문에 이력 전체의 메모를 실어 올 이유가 없다.
     */
    @Query("""
            select ch.photoKey from CookHistory ch
             where ch.recipeId = :recipeId
               and ch.photoKey is not null
            """)
    List<String> findPhotoKeysByRecipeId(@Param("recipeId") Long recipeId);

    /**
     * Recipe 삭제 정리용. 건수만큼 DELETE 를 내보내지 않으려고 벌크로 지운다.
     *
     * <p><b>{@code clearAutomatically = true} 를 붙이지 말 것.</b> 이 메서드는 Recipe 삭제
     * 트랜잭션 안에서 호출되고, 컨텍스트를 비우면 그 트랜잭션의 다른 변경이 조용히 사라진다
     * ({@code UploadObjectRepository.attachIfUnattached} 와 같은 이유).
     */
    @Modifying
    @Query("delete from CookHistory ch where ch.recipeId = :recipeId")
    void deleteByRecipeId(@Param("recipeId") Long recipeId);
}
