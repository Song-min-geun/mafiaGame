package com.example.mafiagame.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    USER_LOGIN_FAILED(HttpStatus.BAD_REQUEST, "USER_LOGIN_FAILED", "로그인에 실패했습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "토큰이 만료되었습니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "방을 찾을 수 없습니다."),
    GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "게임을 찾을 수 없습니다."),
    GAMESTATE_NOT_FOUND(HttpStatus.NOT_FOUND, "GAMESTATE_NOT_FOUND", "게임 상태를 찾을 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "유저를 찾을 수 없습니다."),
    USER_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "USER_ALREADY_EXISTS", "유저가 이미 존재합니다."),
    INSUFFICIENT_PLAYERS(HttpStatus.BAD_REQUEST, "INSUFFICIENT_PLAYERS", "게임을 시작하려면 최소 4명이 필요합니다."),
    GAME_CREATE_IN_PROGRESS(HttpStatus.CONFLICT, "GAME_CREATE_IN_PROGRESS", "게임 생성 중입니다. 잠시 후 다시 시도해주세요."),
    GAME_CREATE_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "GAME_CREATE_INTERRUPTED", "게임 생성 중 인터럽트가 발생했습니다."),
    ;

    private final String code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.message = message;
        this.code = code;
    }

    public CommonException commonException() {
        return new CommonException(this);
    }
}
