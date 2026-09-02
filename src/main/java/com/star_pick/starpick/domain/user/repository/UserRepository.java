package com.star_pick.starpick.domain.user.repository;

import com.star_pick.starpick.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

}
