package com.star_pick.starpick.domain.user.repository;

import com.star_pick.starpick.domain.user.entity.Provider;
import com.star_pick.starpick.domain.user.entity.SocialCredential;
import com.star_pick.starpick.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface SocialCredentialRepository extends JpaRepository<SocialCredential, Long> {
    Optional<SocialCredential> findByProviderAndSocialUid(Provider provider,String socialUid);

    String userIn(Collection<User> users);
}
