package com.example.mafiagame.user.dto.reponse;

import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.domain.UserRole;

public record UserDetailForAdmin(
        Long userId,
        String nickname,
        String userLoginId,
        UserRole userRole
) {
    public UserDetailForAdmin(User user) {
            this(user.getUserId(), user.getNickname(), user.getUserLoginId(), user.getUserRole());
        }
}
