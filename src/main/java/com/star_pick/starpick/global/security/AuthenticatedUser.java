package com.star_pick.starpick.global.security;

/**
 * 인증된 사용자. Controller 는 {@code @AuthenticationPrincipal AuthenticatedUser} 로 받는다.
 *
 * <p>stateless JWT 라 password·authorities·계정 잠금 개념이 없어 UserDetails 를 구현하지 않는다.
 */
public record AuthenticatedUser(Long userId) {}
