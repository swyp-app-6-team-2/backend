package com.star_pick.starpick.domain.recipe.repository;

import com.star_pick.starpick.domain.recipe.domain.Recipe;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    Optional<Recipe> findByIdAndUserId(Long id, Long userId);

    /**
     * 소유한 Recipe 의 한 페이지. 정렬은 {@code Pageable} 이 갖고 있다.
     *
     * <p>{@code Page} 가 count 쿼리까지 수행하므로 총 개수를 따로 세지 않는다.
     */
    Page<Recipe> findByUserId(Long userId, Pageable pageable);

    /**
     * 여러 Recipe 의 재료명을 한 번에 읽는다. 목록의 N+1 을 막는 것이 목적이다.
     *
     * <p>fetch join 을 쓰지 않는 이유는 컬렉션 fetch join 과 페이지네이션을 같이 쓰면 Hibernate 가
     * 전체를 읽어 메모리에서 자르기 때문이다. 그러면 페이지네이션이 무의미해진다.
     *
     * <p>표시 순서를 정렬에 넣어 호출부가 다시 정렬하지 않게 한다.
     *
     * <p>소유권 조건을 쿼리 안에 넣는다. 이 Repository 의 다른 조회 메서드와 같은 규칙이며,
     * 호출부가 이미 소유자로 거른 ID 만 넘긴다는 전제에 기대지 않기 위해서다.
     */
    @Query("""
            select new com.star_pick.starpick.domain.recipe.repository.RecipeIngredientNameRow(
                ri.recipe.id, ri.name)
            from RecipeIngredient ri
            where ri.recipe.userId = :userId and ri.recipe.id in :recipeIds
            order by ri.recipe.id asc, ri.displayOrder asc
            """)
    List<RecipeIngredientNameRow> findIngredientNames(
            @Param("userId") Long userId, @Param("recipeIds") List<Long> recipeIds);

    /** 다른 도메인의 존재·소유권 확인용. Entity 를 읽지 않으므로 경계를 넘겨줄 것이 없다. */
    boolean existsByIdAndUserId(Long id, Long userId);

    /**
     * 수정 대상 Recipe 를 잠근다.
     *
     * <p>소유권 조건을 잠금 쿼리 안에 넣는다. 먼저 잠그고 나중에 소유자를 비교하면 권한 없는
     * 요청이 실제 소유자의 수정을 블로킹할 수 있다.
     *
     * <p>fetch join 을 넣지 않는다. PostgreSQL 이 outer join 의 nullable 쪽에 FOR UPDATE 를
     * 허용하지 않는다. 자식은 부모 행 잠금으로 이미 직렬화된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Recipe r where r.id = :id and r.userId = :userId")
    Optional<Recipe> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
