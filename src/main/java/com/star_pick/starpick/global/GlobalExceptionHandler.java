package com.star_pick.starpick.global;

import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import com.star_pick.starpick.global.exception.ErrorCode;
import com.star_pick.starpick.global.exception.ErrorData;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * MVC 표준 예외의 HTTP status 판단은 ResponseEntityExceptionHandler 에 맡기고 본문만
 * 공통 Envelope 로 바꾼다. 핸들러를 손으로 열거하면 열거를 빠뜨린 예외가 전부 500 이 된다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // domain/auth 소유라 이번 단계에서 손대지 않는다.
    // Auth ErrorCode 가 도입되면 BusinessException 으로 합쳐지고 아래 두 핸들러는 사라진다.
    @ExceptionHandler(InvalidSocialTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidSocialToken(InvalidSocialTokenException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), e.getMessage()));
    }

    @ExceptionHandler(SocialAuthServerException.class)
    public ResponseEntity<ApiResponse<Void>> handleSocialAuthServer(SocialAuthServerException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<ErrorData>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus()).body(ApiResponse.error(errorCode));
    }

    /**
     * ExceptionTranslationFilter 가 AccessDeniedHandler 로 넘기도록 그대로 되던진다.
     * 아래 Exception fallback 이 먼저 삼키면 403 이 500 이 된다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void rethrowAccessDenied(AccessDeniedException e) throws AccessDeniedException {
        throw e;
    }

    /**
     * ExceptionTranslationFilter 가 AuthenticationEntryPoint 로 넘기도록 그대로 되던진다.
     * rethrowAccessDenied 와 같은 이유이며, 없으면 401 이어야 할 실패가 500 이 된다.
     */
    @ExceptionHandler(AuthenticationException.class)
    public void rethrowAuthentication(AuthenticationException e) throws AuthenticationException {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorData>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<ErrorData.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorData.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.REQUEST_VALIDATION_FAILED, errors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.INVALID_REQUEST_FORMAT));
    }

    /**
     * PathVariable·RequestParam 의 타입 불일치. 본문 역직렬화 실패와 같은 성격이므로 같은 code 를 쓴다.
     *
     * <p>이게 없으면 {@code GET /api/v1/recipes/abc} 가 data.code 없는 400 으로 나가 FE 가 분기할 수 없다.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.INVALID_REQUEST_FORMAT));
    }

    /**
     * 나머지 MVC 표준 예외(404·405·415 등). status 는 Spring 이 정하고 본문만 Envelope 로 바꾼다.
     *
     * <p>handleExceptionInternal 이 아니라 그 꼬리의 createResponseEntity 를 재정의한다.
     * 그래야 이미 commit 된 응답에 본문을 쓰지 않는 상위 구현의 방어가 유지된다.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(ApiResponse.error(statusCode.value(), "요청을 처리할 수 없습니다."));
    }
}
