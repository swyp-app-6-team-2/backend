package com.star_pick.starpick.global;

import com.star_pick.starpick.domain.auth.exception.InvalidSocialTokenException;
import com.star_pick.starpick.domain.auth.exception.SocialAuthServerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 상태코드 - 401
    @ExceptionHandler(InvalidSocialTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidSocialToken(InvalidSocialTokenException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), e.getMessage()));
    }

    // 상태코드 - 500
    @ExceptionHandler(SocialAuthServerException.class)
    public ResponseEntity<ApiResponse<Void>> handleSocialAuthServer(SocialAuthServerException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), e.getMessage()));
    }
}
