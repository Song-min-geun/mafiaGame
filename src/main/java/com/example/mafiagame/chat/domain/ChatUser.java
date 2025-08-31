package com.example.mafiagame.chat.domain;

import java.util.Date;

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
    private boolean isOnline;
    private Date lastSeen;
}
