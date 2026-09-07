package com.star_pick.starpick.domain.recipe.repository;

import com.star_pick.starpick.domain.recipe.domain.Recipe;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    Optional<Recipe> findByIdAndUserId(Long id, Long userId);

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
