package com.example.mafiagame.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_users")
@IdClass(ChatUserId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUser {
    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Id
    @Column(name = "room_id", nullable = false)
    private String roomId;
    
    @Column(name = "user_name", nullable = false)
    private String userName;
    
    @Column(name = "is_host", nullable = false)
    @Builder.Default
    private boolean isHost = false;  // 방장 여부
}
