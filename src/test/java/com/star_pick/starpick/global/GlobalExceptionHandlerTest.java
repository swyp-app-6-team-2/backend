package com.star_pick.starpick.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공통 실패 응답 계약 테스트.
 *
 * <p>Spring Context 를 띄우지 않는다. 검증 대상인 advice 와 최소한의 컨트롤러만
 * standalone MockMvc 에 올린다. 테스트 전용 컨트롤러를 운영 소스 트리에 두지 않기 위한 선택이다.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("G1 BusinessException 은 ErrorCode 의 status 와 상수명을 그대로 내보낸다")
    void businessException() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("테스트 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.data.code").value("TEST_RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.data.errors").doesNotExist());
    }

    @Test
    @DisplayName("G2 Bean Validation 실패는 400 REQUEST_VALIDATION_FAILED 와 errors 를 준다")
    void validationSingleError() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":1,\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors.length()").value(1))
                .andExpect(jsonPath("$.data.errors[0].field").value("name"))
                .andExpect(jsonPath("$.data.errors[0].reason").value("이름은 필수입니다."));
    }

    @Test
    @DisplayName("G3 실패 필드가 여러 개면 errors 도 여러 개다")
    void validationMultipleErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors.length()").value(2));
    }

    @Test
    @DisplayName("G4 중첩 배열의 필드 경로가 가공 없이 items[1].name 으로 나온다")
    void validationNestedPath() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"quantity\":1,\"items\":[{\"name\":\"ok\"},{\"name\":\"\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[0].field").value("items[1].name"));
    }

    @Test
    @DisplayName("G5 깨진 JSON 은 400 INVALID_REQUEST_FORMAT 이다")
    void malformedJson() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"))
                .andExpect(jsonPath("$.data.errors").doesNotExist());
    }

    @Test
    @DisplayName("G6 타입 불일치는 Validation 이 아니라 INVALID_REQUEST_FORMAT 이다")
    void typeMismatch() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"quantity\":\"열개\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    @DisplayName("G7 예상하지 못한 예외는 500 이고 내부 정보를 노출하지 않는다")
    void unexpectedException() throws Exception {
        String body = mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.data.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("secret-detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("trace");
    }

    @Test
    @DisplayName("G8 성공 응답은 status, message, data 순서를 유지한다")
    void successEnvelopeOrder() throws Exception {
        String body = mockMvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).startsWith("{\"status\":200,\"message\":");
    }

    @Getter
    @RequiredArgsConstructor
    private enum TestErrorCode implements ErrorCode {

        TEST_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "테스트 리소스를 찾을 수 없습니다.");

        private final HttpStatus status;
        private final String message;

        @Override
        public String getCode() {
            return name();
        }
    }

    private record TestRequest(
            @NotBlank(message = "이름은 필수입니다.") String name,
            @NotNull(message = "수량은 필수입니다.") Integer quantity,
            @Valid List<TestItem> items
    ) {}

    private record TestItem(@NotBlank(message = "항목명은 필수입니다.") String name) {}

    @RestController
    @RequestMapping("/test")
    private static class TestController {

        @GetMapping("/ok")
        ApiResponse<String> ok() {
            return ApiResponse.ok("성공했습니다.", "value");
        }

        @GetMapping("/business")
        ApiResponse<Void> business() {
            throw new BusinessException(TestErrorCode.TEST_RESOURCE_NOT_FOUND);
        }

        @GetMapping("/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("secret-detail");
        }

        @PostMapping("/validate")
        ApiResponse<Void> validate(@Valid @RequestBody TestRequest request) {
            return ApiResponse.ok("검증을 통과했습니다.", null);
        }
    }
}
