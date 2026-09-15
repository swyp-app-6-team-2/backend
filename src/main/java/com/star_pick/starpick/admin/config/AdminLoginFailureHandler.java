package com.star_pick.starpick.admin.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * 로그인 시도 제한을 두지 않는 대신 실패를 WARN 으로 남겨 무차별 대입을 알아챈다.
 *
 * <p>입력한 아이디·비밀번호는 남기지 않는다. IP 는 forward-headers 설정 덕분에 Caddy 가 아니라 실제 접속 IP 다.
 */
@Slf4j
public class AdminLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    public AdminLoginFailureHandler() {
        super("/admin/login?error");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        log.warn("관리자 로그인 실패. ip={}", request.getRemoteAddr());
        super.onAuthenticationFailure(request, response, exception);
    }
}
