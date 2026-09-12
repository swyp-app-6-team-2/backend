package com.star_pick.starpick.global.security.config;

import com.star_pick.starpick.global.security.ApiSecurityErrorHandler;
import com.star_pick.starpick.global.security.jwt.JwtAuthenticationFilter;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain staticImageFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/images/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Security 기본 헤더는 no-store 라 이미지를 매 요청 다시 받게 된다.
                // 캐시 정책은 StaticImageCacheConfig 가 소유하므로 이 경로에서만 끈다.
                .headers(headers -> headers.cacheControl(cache -> cache.disable()))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider, JsonMapper jsonMapper)
            throws Exception {

        ApiSecurityErrorHandler errorHandler = new ApiSecurityErrorHandler(jsonMapper);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ERROR dispatch 까지 인증을 요구하면 실제 오류 대신 401 이 나간다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                )
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
