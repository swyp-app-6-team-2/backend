package com.star_pick.starpick.domain.auth.client.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserInfoResponse (
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
){
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoAccount(String email) { }
}
