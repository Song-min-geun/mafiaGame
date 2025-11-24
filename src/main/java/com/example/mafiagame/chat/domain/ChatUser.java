package com.example.mafiagame.chat.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUser {

    @Id
    private Long id;

    @Column(nullable = false)
    private String userId; // User의 userLoginId

    @Column(nullable = false)
    private String userName; // User의 nickname

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    @JsonBackReference
    private ChatRoom room;

    @Builder.Default
    private boolean isHost = false;
}