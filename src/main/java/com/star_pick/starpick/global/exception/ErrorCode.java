package com.star_pick.starpick.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 각 도메인이 소유하는 ErrorCode 의 공통 계약.
 *
 * <p>구현 enum 의 상수명이 그대로 공개 code 가 된다. 상수명을 바꾸면 FE 분기가 깨진다.
 */
public interface ErrorCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();
}
