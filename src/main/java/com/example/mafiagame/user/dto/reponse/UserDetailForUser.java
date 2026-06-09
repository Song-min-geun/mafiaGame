package com.example.mafiagame.user.dto.reponse;

import com.example.mafiagame.user.domain.Users;

public record UserDetailForUser(
        String nickname,
        String title,
        String titleDisplayName
) {
    public UserDetailForUser(String nickname) {
        this(nickname, null, null);
    }

    public UserDetailForUser(Users users) {
        this(users.getNickname(), users.getTitle().name(), users.getTitle().getDisplayName());
    }
}
