package com.example.mafiagame.chat.domain;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoom {
    private String roomId;
    private String roomName;
    private String hostId;
    private int maxPlayers;
    private List<ChatUser> participants;
    private boolean isGameActive;

    public void addParticipant(ChatUser participant) {
        if (participants == null) {
            participants = new ArrayList<>();
        }
        participants.add(participant);
    }

    public void removeParticipant(String userId) {
        if (participants != null) {
            participants.removeIf(p -> p.getUserId().equals(userId));
        }
    }

    public boolean isUserInRoom(String userId) {
        return participants != null && 
               participants.stream().anyMatch(p -> p.getUserId().equals(userId));
    }

    public boolean isFull() {
        return participants != null && participants.size() >= maxPlayers;
    }

    public boolean canStartGame() {
        return participants != null && participants.size() >= 4 && !isGameActive;
    }

    public void startGame() {
        this.isGameActive = true;
    }

    public void endGame() {
        this.isGameActive = false;
    }
}
