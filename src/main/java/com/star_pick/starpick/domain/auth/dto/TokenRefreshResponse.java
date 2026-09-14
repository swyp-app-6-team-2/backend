package com.star_pick.starpick.domain.auth.dto;

public record TokenRefreshResponse(String accessToken, String refreshToken) { }
