package com.example.mafiagame.game.domain.state;

import java.io.Serializable;

import com.example.mafiagame.chat.domain.ChatUser;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GamePlayerState implements Serializable {
    private static final long serialVersionUID = 1L;

    private String playerId;
    private String playerName;

    @Builder.Default
    private PlayerRole role = null;
    @Builder.Default
    private Team team = null;

    @Builder.Default
    private boolean isAlive = true;

    public static GamePlayerState from(ChatUser chatUser) {
        return GamePlayerState.builder()
                .playerId(chatUser.getUserId())
                .playerName(chatUser.getUserName())
                .isAlive(true)
                .build();
    }
}
