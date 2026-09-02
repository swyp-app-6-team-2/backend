package com.star_pick.starpick.domain.user.entity;

import lombok.Getter;

@Getter
public enum Provider {
    KAKAO("카카오"),
    NAVER("네이버"),
    GOOGLE("구글"),
    APPLE("애플");

    private final String displayName;

    Provider(String displayName) {
        this.displayName = displayName;
    }
}
