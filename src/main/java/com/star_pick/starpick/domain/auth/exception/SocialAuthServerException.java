package com.star_pick.starpick.domain.auth.exception;

import com.star_pick.starpick.domain.user.entity.Provider;

public class SocialAuthServerException extends RuntimeException {
    public SocialAuthServerException(Provider provider) {
        super(provider.getDisplayName() + "인증 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.");
    }
}
