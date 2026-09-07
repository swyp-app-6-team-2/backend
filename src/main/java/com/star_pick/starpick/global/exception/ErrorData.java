package com.star_pick.starpick.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 실패 응답의 data 본문. errors 가 null 이면 직렬화에서 빠진다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorData(String code, List<FieldError> errors) {

    public record FieldError(String field, String reason) {}
}
