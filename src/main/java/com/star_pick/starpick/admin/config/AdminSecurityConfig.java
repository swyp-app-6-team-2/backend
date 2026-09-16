package com.star_pick.starpick.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

/**
 * 관리자 페이지(/admin/**) 전용 필터 체인. 앱 API 의 {@code SecurityConfig} 는 건드리지 않는다.
 *
 * <p>{@code SecurityConfig} 의 API 체인({@code @Order(1)})에는 securityMatcher 가 없어 모든 요청과 매칭된다.
 * 그 뒤에 두면 이 체인은 영영 선택되지 않고 Spring Security 가 기동을 거부하므로 앞 순서로 둔다.
 */
@Configuration
public class AdminSecurityConfig {

    /**
     * 관리자 계정을 Bean 으로 둔다. Bean 이 없으면 Boot 가 쓰이지 않는 기본 계정을 만들고 기동 로그에
     * 임시 비밀번호 WARN 을 남긴다. 앱 API 체인은 폼 로그인·HTTP Basic 이 꺼져 있어 이 계정으로 인증되는 경로가 없다.
     *
     * <p>{@code @ConfigurationProperties} 가 아니라 {@code @Value} 로 받는 것이 의도다. 전자는 해석되지 않은
     * {@code ${ADMIN_PASSWORD}} 를 문자열 그대로 바인딩해 추측 가능한 계정으로 기동한다. {@code @Value} 는
     * 환경변수가 없으면 기동에 실패하고(JwtProvider·GcsConfig 와 같다), 빈 값은 아래 검사가 막는다.
     * BCrypt 는 72바이트를 넘는 비밀번호를 거절하므로 너무 긴 값도 기동 실패가 된다.
     */
    @Bean
    UserDetailsService adminUserDetailsService(@Value("${admin.username}") String username,
                                               @Value("${admin.password}") String password) {
        Assert.hasText(username, "admin.username(ADMIN_USERNAME)이 비어 있습니다.");
        Assert.hasText(password, "admin.password(ADMIN_PASSWORD)가 비어 있습니다.");
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build());
    }

    @Bean
    @Order(-1)
    SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/admin/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        // Discord 알림 링크로 들어오면 로그인 뒤 그 문의로 돌아간다. 저장된 요청이 없을 때만 목록이다.
                        .defaultSuccessUrl("/admin/inquiries")
                        .failureHandler(new AdminLoginFailureHandler()))
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login")
                        // 남은 세션 쿠키가 다음 요청을 "만료된 세션"으로 보이게 하지 않도록 지운다.
                        .deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.invalidSessionUrl("/admin/login?expired"))
                // 세션 만료로 토큰이 없으면 invalidSessionUrl 이 처리하지만, 다른 탭에서 다시 로그인해 토큰이 바뀐 뒤
                // 저장하면 InvalidCsrfTokenException 이 403 으로 /error 에 가서 앱 API 체인의 기본 오류 화면이 뜬다.
                // 관리자 체인에는 역할 검사가 없어 403 은 CSRF 뿐이므로 모두 로그인 화면으로 보낸다.
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, denied) ->
                        response.sendRedirect(request.getContextPath() + "/admin/login?expired")));
        return http.build();
    }
}
