package com.star_pick.starpick.domain.user.repository;

import com.star_pick.starpick.domain.user.entity.UserCustomIngredient;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCustomIngredientRepository extends JpaRepository<UserCustomIngredient, Long> {

    List<UserCustomIngredient> findByUserId(Long userId);
}
