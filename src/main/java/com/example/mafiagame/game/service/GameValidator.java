package com.example.mafiagame.game.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GameValidator {

    private static final int MIN_PARTICIPANTS = 4;

    public void validateCreateGame(ChatRoom chatRoom) {
        if (chatRoom == null) {
            throw new IllegalArgumentException("채팅방을 찾을 수 없습니다.");
        }

        List<ChatUser> participants = chatRoom.getParticipants();
        if (participants == null || participants.size() < MIN_PARTICIPANTS) {
            throw new IllegalArgumentException("게임을 시작하려면 최소 " + MIN_PARTICIPANTS + "명이 필요합니다.");
        }
    }
}
