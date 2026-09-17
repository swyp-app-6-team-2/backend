package com.star_pick.starpick.domain.user.repository;

import com.star_pick.starpick.domain.user.entity.UserCustomIngredient;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCustomIngredientRepository extends JpaRepository<UserCustomIngredient, Long> {

    List<UserCustomIngredient> findByUserId(Long userId);

    @Modifying
    @Query("delete from UserCustomIngredient c where c.userId = :userId and c.id in :ids")
    int deleteByUserIdAndIdIn(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    @Modifying
    @Query("delete from UserCustomIngredient c where c.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
