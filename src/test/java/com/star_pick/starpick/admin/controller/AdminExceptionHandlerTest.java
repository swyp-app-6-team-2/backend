package com.star_pick.starpick.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.star_pick.starpick.domain.inquiry.exception.InquiryErrorCode;
import com.star_pick.starpick.global.GlobalExceptionHandler;
import com.star_pick.starpick.global.exception.BusinessException;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 관리자 컨트롤러의 예외가 전역 JSON 처리기보다 먼저 HTML 오류 화면으로 가는지 확인한다.
 *
 * <p>테스트 컨트롤러를 관리자 패키지에 둬야 {@code basePackages} 범위에 든다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestAdminController())
            .setControllerAdvice(new GlobalExceptionHandler(), new AdminExceptionHandler())
            .build();

    @Test
    @DisplayName("없는 문의는 404 오류 화면이다")
    void notFound() throws Exception {
        mockMvc.perform(get("/test-admin/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/error"))
                .andExpect(model().attribute("message", "문의를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("타입 변환 실패는 400 오류 화면이다")
    void typeMismatch() throws Exception {
        mockMvc.perform(get("/test-admin/number").param("value", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("admin/error"))
                .andExpect(model().attribute("message", "잘못된 요청입니다."));
    }

    @Test
    @DisplayName("예상 밖 예외는 500 오류 화면이고 method·path 를 ERROR 로 남기되 query string 은 남기지 않는다")
    void unexpected(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test-admin/boom").queryParam("secret", "do-not-log"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("admin/error"))
                .andExpect(model().attribute("message", "오류가 발생했습니다."));

        // Boot 로깅 초기화 전에 이 클래스가 먼저 돌면 DispatcherServlet DEBUG 로그가 query string 을 찍는다.
        // 그 줄은 이 처리기의 책임이 아니므로 관리자 예외 로그 줄만 골라 확인한다.
        List<String> lines = output.getAll().lines()
                .filter(line -> line.contains("관리자 페이지 처리되지 않은 예외"))
                .toList();
        assertThat(lines).singleElement(InstanceOfAssertFactories.STRING)
                .contains("method=GET, path=/test-admin/boom")
                .doesNotContain("do-not-log");
    }

    @Controller
    static class TestAdminController {

        @GetMapping("/test-admin/not-found")
        String notFound() {
            throw new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND);
        }

        @GetMapping("/test-admin/number")
        String number(@RequestParam int value) {
            return "admin/login";
        }

        @GetMapping("/test-admin/boom")
        String boom() {
            throw new IllegalStateException("테스트 예외");
        }
    }
}
