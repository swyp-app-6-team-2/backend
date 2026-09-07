package com.star_pick.starpick.global;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.star_pick.starpick.global.exception.ErrorCode;
import com.star_pick.starpick.global.exception.ErrorData;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@Builder
@JsonPropertyOrder({"status", "message", "data"})
public class ApiResponse<T> {
    private int status;
    private String message;
    private T data;

    //성공응답 - 200
    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .status(HttpStatus.OK.value())
                .message(message)
                .data(data)
                .build();
    }

    //성공응답 - 201
    public static <T> ApiResponse<T> created(String message, T data) {
        return ApiResponse.<T>builder()
                .status(HttpStatus.CREATED.value())
                .message(message)
                .data(data)
                .build();
    }

    //실패응답 - ErrorCode 기반. data 는 {"code": ...}
    public static ApiResponse<ErrorData> error(ErrorCode errorCode) {
        return error(errorCode, null);
    }

    //실패응답 - Validation 상세 포함. data 는 {"code": ..., "errors": [...]}
    public static ApiResponse<ErrorData> error(ErrorCode errorCode, List<ErrorData.FieldError> errors) {
        return ApiResponse.<ErrorData>builder()
                .status(errorCode.getStatus().value())
                .message(errorCode.getMessage())
                .data(new ErrorData(errorCode.getCode(), errors))
                .build();
    }

    //실패응답 - code 없는 Envelope (MVC 표준 실패, Auth 예외)
    public static <T> ApiResponse<T> error(int status, String message) {
        return ApiResponse.<T>builder()
                .status(status)
                .message(message)
                .data(null)
                .build();

    }
}
