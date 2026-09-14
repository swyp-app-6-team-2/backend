package com.star_pick.starpick.domain.auth.dto;

public record SignupResponse(Long userId, String accessToken, String refreshToken) { }
