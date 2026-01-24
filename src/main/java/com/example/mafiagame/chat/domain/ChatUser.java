package com.example.mafiagame.chat.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String userName;

    @Builder.Default
    private boolean isHost = false;

    public void assignAsHost() {
        this.isHost = true;
    }

    public void removeHost() {
        this.isHost = false;
    }
}