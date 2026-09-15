package com.star_pick.starpick.admin.controller;

import com.star_pick.starpick.global.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 관리자 컨트롤러의 예외를 HTML 오류 화면으로 바꾼다.
 *
 * <p>전역 {@code GlobalExceptionHandler} 는 모든 컨트롤러에 적용돼 JSON 을 돌려주므로, 관리자 패키지로 범위를
 * 좁히고 가장 먼저 적용한다. 전역 처리기를 거치지 않으므로 500 로그도 여기서 같은 규칙으로 직접 남긴다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(basePackages = "com.star_pick.starpick.admin")
public class AdminExceptionHandler {

    public static final String ERROR_VIEW = "admin/error";

    /** 없는 문의(404)와 잘못된 페이지 번호(400). 사용자 입력 문제라 로그를 남기지 않는다. */
    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException e, HttpServletResponse response, Model model) {
        HttpStatus status = e.getErrorCode().getStatus();
        String message = status == HttpStatus.NOT_FOUND ? "문의를 찾을 수 없습니다." : "잘못된 요청입니다.";
        return render(response, model, status, message);
    }

    /** 없는 enum 값, 숫자가 아닌 page·inquiryId. */
    @ExceptionHandler({TypeMismatchException.class, MissingServletRequestParameterException.class})
    public String badRequest(Exception e, HttpServletResponse response, Model model) {
        return render(response, model, HttpStatus.BAD_REQUEST, "잘못된 요청입니다.");
    }

    /** query string·폼 본문·헤더는 사용자 입력이 섞일 수 있어 남기지 않는다. */
    @ExceptionHandler(Exception.class)
    public String unexpected(Exception e, HttpServletRequest request, HttpServletResponse response, Model model) {
        log.error("관리자 페이지 처리되지 않은 예외. method={}, path={}",
                request.getMethod(), request.getRequestURI(), e);
        return render(response, model, HttpStatus.INTERNAL_SERVER_ERROR, "오류가 발생했습니다.");
    }

    private String render(HttpServletResponse response, Model model, HttpStatus status, String message) {
        response.setStatus(status.value());
        model.addAttribute("status", status.value());
        model.addAttribute("message", message);
        return ERROR_VIEW;
    }
}
