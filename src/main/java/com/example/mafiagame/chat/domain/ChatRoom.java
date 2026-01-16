package com.example.mafiagame.chat.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "chat_rooms")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class ChatRoom {
    @Id
    @Column(name = "room_id")
    private String roomId;

    @Column(name = "room_name")
    private String roomName;

    @Column(name = "host_id", nullable = false)
    private String hostId;

    @Column(name = "host_name", nullable = false)
    private String hostName;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference
    @Builder.Default
    private List<ChatUser> participants = new ArrayList<>();

    @Builder.Default
    private int maxPlayers = 8;

    @Column(name = "is_playing")
    private boolean isPlaying;

    public ChatRoom(String roomName, String hostId, String hostName) {
        this.roomId = UUID.randomUUID().toString();
        if (roomName == null || roomName.trim().isEmpty()) {
            this.roomName = "마피아 게임 #" + (int) (Math.random() * 1000);
        } else {
            this.roomName = roomName;
        }
        this.hostId = hostId;
        this.hostName = hostName;
    }

    // 채팅방에 새로운 참가자를 추가합니다. 중복 참여는 방지됩니다.

    public void addParticipant(String userId, String userName, boolean isHost) {

        if (this.participants == null) {

            this.participants = new ArrayList<>();

        }

        if (isParticipant(userId))
            return; // 중복 참여 방지

        ChatUser participant = ChatUser.builder()

                .userId(userId)

                .userName(userName)

                .room(this)

                .isHost(isHost)

                .build();

        this.participants.add(participant);

    }

    // 특정 참가자를 채팅방에서 제거합니다. 만약 방장이 나가면 다른 참가자에게 방장 역할을 위임합니다.

    public String removeParticipant(String userId) {
        if (this.participants == null) {

            return null;

        }

        ChatUser userToRemove = this.participants.stream()

                .filter(p -> p.getUserId().equals(userId))

                .findFirst().orElse(null);

        if (userToRemove != null) {

            this.participants.remove(userToRemove);

            // 방장이 나갔을 경우, 다른 참가자가 있으면 방장 위임

            if (userToRemove.isHost() && !this.participants.isEmpty()) {

                ChatUser newHost = this.participants.get(0);

                newHost.setHost(true);

                this.hostId = newHost.getUserId();

                this.hostName = newHost.getUserName();

            }

            return userToRemove.getUserName();

        }

        return null;

    }

    // 주어진 ID의 사용자가 이미 채팅방에 있는지 확인합니다.

    public boolean isParticipant(String userId) {

        if (this.participants == null) {

            return false;

        }

        return this.participants.stream().anyMatch(p -> p.getUserId().equals(userId));

    }
}
