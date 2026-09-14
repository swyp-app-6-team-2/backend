package com.star_pick.starpick.domain.auth.client;

import com.star_pick.starpick.domain.user.entity.Provider;

public interface SocialUserInfoClient {
    Provider getProvider();
    SocialUserInfo getUserInfo(String authToken);

    default SocialUserInfo getUserInfo(String authToken, String nonce) {
        return getUserInfo(authToken);
    }
}
