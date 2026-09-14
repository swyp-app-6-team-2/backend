package com.star_pick.starpick.domain.auth.client.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverUserInfoResponse (
        String resultcode,
        String message,
        NaverAccount response
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NaverAccount(String id, String email) {
    }
}
