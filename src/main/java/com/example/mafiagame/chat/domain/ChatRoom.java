package com.example.mafiagame.chat.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_rooms")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoom {
    @Id
    @Column(name = "room_id", nullable = false)
    private String roomId;
    
    @Column(name = "room_name", nullable = false)
    private String roomName;
    
    @Column(name = "host_id", nullable = false)
    private String hostId;
    
    @Column(name = "max_players", nullable = false)
    private int maxPlayers;
    
    @Column(name = "current_game_id")
    private String currentGameId;  // 현재 진행 중인 게임 ID (외래키 참조)
    
    @Column(name = "is_game_active", nullable = false)
    private boolean isGameActive;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // JPA에서 제외하고 서비스에서 관리
    @Transient
    private List<ChatUser> participants;
    
    @Transient
    private List<String> gameHistoryIds;  // 게임 히스토리 ID 목록

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

    public void startGame(String gameId) {
        this.currentGameId = gameId;
        this.isGameActive = true;
        this.updatedAt = LocalDateTime.now();
        
        if (gameHistoryIds == null) {
            gameHistoryIds = new ArrayList<>();
        }
        gameHistoryIds.add(gameId);
    }

    public void endGame() {
        this.currentGameId = null;
        this.isGameActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isGameActive() {
        return isGameActive;
    }

    public String getCurrentGameId() {
        return currentGameId;
    }

    public List<String> getGameHistoryIds() {
        return gameHistoryIds != null ? gameHistoryIds : new ArrayList<>();
    }
    
    // 생성 시 자동으로 시간 설정
    public void setCreatedAt() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
