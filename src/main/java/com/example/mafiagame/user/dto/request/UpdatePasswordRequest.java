package com.example.mafiagame.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePasswordRequest(
        @NotBlank(message = "현재 비밀번호를 입력해주세요.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수 입력 값입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상으로 입력해주세요.")
        String newPassword
) {}
