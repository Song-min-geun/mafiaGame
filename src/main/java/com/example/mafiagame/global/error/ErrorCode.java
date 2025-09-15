package com.example.mafiagame.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    USER_LOGIN_FAILED(HttpStatus.BAD_REQUEST, "USER_LOGIN_FAILED", "User Login Failed"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid Token"),
    ;
    private final String code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.message = message;
        this.code = code;
    }

    public CommonException commonException() {return new CommonException(this);}
}
