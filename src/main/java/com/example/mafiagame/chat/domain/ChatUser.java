package com.example.mafiagame.chat.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUser {
    private String userId;
    private String userName;
    @Builder.Default
    private boolean isHost = false;  // 방장 여부
}
