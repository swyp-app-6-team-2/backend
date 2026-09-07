package com.star_pick.starpick.global.security.jwt;

import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer token 을 검증해 SecurityContext 를 채운다.
 *
 * <p>검증에 실패해도 응답을 쓰지 않는다. 인증 없이 체인을 계속 태우고 401 생성은
 * AuthenticationEntryPoint 가 단독으로 담당한다. permitAll 경로에 만료 토큰이 붙어 와도
 * 정상 처리되어야 하기 때문이다.
 *
 * <p>Bean 으로 등록하지 않는다. {@code @Component} 를 붙이면 Boot 가 서블릿 필터 체인에도
 * 자동 등록해 요청당 두 번 실행된다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthenticatedUser principal = new AuthenticatedUser(jwtProvider.parseAccessToken(token));
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, List.of());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // 인증하지 않고 넘긴다. 예상 가능한 실패이고 토큰 문자열은 민감정보라 로그로 남기지 않는다(04-3).
            }
        }
        chain.doFilter(request, response);
    }

    /** RFC 7235 상 scheme 은 대소문자를 구분하지 않는다. */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
