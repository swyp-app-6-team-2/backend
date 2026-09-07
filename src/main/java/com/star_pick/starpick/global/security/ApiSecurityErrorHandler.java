package com.star_pick.starpick.global.security;

import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security Filter Chain 의 실패를 공통 Envelope 로 변환한다.
 *
 * <p>GlobalExceptionHandler 는 DispatcherServlet 안쪽만 담당하므로 필터 단계의 실패는
 * 여기서 직접 응답을 쓴다(04-3).
 *
 * <p>JsonMapper 는 Jackson 3 다. Boot 4 에는 com.fasterxml ObjectMapper bean 이 없다.
 */
@RequiredArgsConstructor
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, CommonErrorCode.ACCESS_DENIED);
    }

    private void write(HttpServletResponse response, CommonErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), ApiResponse.error(errorCode));
    }
}
