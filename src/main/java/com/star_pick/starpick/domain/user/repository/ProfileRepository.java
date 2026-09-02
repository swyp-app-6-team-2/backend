package com.star_pick.starpick.domain.user.repository;

import com.star_pick.starpick.domain.user.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

}
