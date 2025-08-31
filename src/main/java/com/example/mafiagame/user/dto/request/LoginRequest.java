package com.example.mafiagame.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "아이디를 입력하세요.")
        @Size(min = 5, max = 15, message = "아이디는 최소 5글자 최대 15글자입니다.")
        String userLoginId,
        @NotBlank(message = "비밀번호를 입력하세요.")
        @Size(min = 10, max = 20, message = "비밀번호는 최소 10글자 최대 20글자입니다.")
        String userLoginPassword
) {
}
