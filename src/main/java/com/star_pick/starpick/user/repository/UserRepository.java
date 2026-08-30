package com.star_pick.starpick.user.repository;

import com.star_pick.starpick.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

}
