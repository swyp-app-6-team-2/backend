package com.star_pick.starpick.global.security.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actuator 는 관리 포트(8081)에만 매핑되고 그 포트는 호스트 loopback 에만 공개된다.
 * 그런데 SecurityFilterChain 을 직접 정의하면 Spring 이 기본으로 넣어주던 actuator 예외가
 * 물러나 SecurityConfig 의 anyRequest().authenticated() 가 관리 포트까지 잠근다(2026-09-19 실측 401).
 * 배포 검증과 Alloy 스크랩이 인증 없이 들어와야 하므로 이 체인을 SecurityConfig 앞에 둔다.
 * AdminSecurityConfig 가 -1 을 쓰고 있어 -2 로 둔다. 같은 값이면 두 체인의 상대 순서가 정의되지 않는다.
 */
@Configuration
public class ActuatorSecurityConfig {

    @Bean
    @Order(-2)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // toAnyEndpoint() 가 아니라 두 개를 명시한다. 그러면 인증 면제 범위가
                // exposure.include 설정에 묶이지 않는다 — 나중에 디버깅하려고 "*" 를 켜도
                // /actuator/env(JWT_SECRET 이 보인다)와 /actuator/heapdump 는 인증 뒤에 남는다.
                .securityMatcher(EndpointRequest.to("health", "prometheus"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
