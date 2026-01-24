package com.example.mafiagame.user.dto.reponse;

import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.domain.UserRole;

public record UserDetailForAdmin(
                Long userId,
                String nickname,
                String userLoginId,
                UserRole userRole) {
        public UserDetailForAdmin(Users users) {
                this(users.getUserId(), users.getNickname(), users.getUserLoginId(), users.getUserRole());
        }
}
