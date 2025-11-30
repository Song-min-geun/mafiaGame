package com.example.mafiagame.chat.repository;

import com.example.mafiagame.chat.domain.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<ChatRoom,String> {
}
