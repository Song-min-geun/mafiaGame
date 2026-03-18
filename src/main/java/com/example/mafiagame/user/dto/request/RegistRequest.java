package com.example.mafiagame.user.dto.request;

import com.example.mafiagame.user.domain.Users;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistRequest(
                @NotBlank @Size(min = 2, max = 10, message = "닉네임 최소 2글자 최대 10글자입니다.") String nickname,

                @NotBlank(message = "아이디는 필수 입력 값입니다.") @Size(min = 5, max = 15, message = "아이디는 최소 5글자 최대 15글자입니다.") String userLoginId,

                @NotBlank(message = "비밀번호는 필수 입력 값입니다.") @Size(min = 10, max = 20, message = "비밀번호는 최소 10글자 최대 20글자입니다.") String userLoginPassword) {

        public Users toEntity() {
                return Users.builder()
                                .nickname(nickname)
                                .userLoginId(userLoginId)
                                .userLoginPassword(userLoginPassword)
                                .build();
        }
}
