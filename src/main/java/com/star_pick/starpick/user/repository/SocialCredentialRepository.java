package com.star_pick.starpick.user.repository;

import com.star_pick.starpick.user.entity.SocialCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialCredentialRepository extends JpaRepository<SocialCredential, Long> {

}
