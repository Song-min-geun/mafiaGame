package com.example.mafiagame;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.dto.request.CreateRoomRequest;
import com.example.mafiagame.chat.dto.request.JoinRoomRequest;
import com.example.mafiagame.chat.service.ChatRoomService;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.domain.UserRole;
import com.example.mafiagame.user.repository.UsersRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MafiagameApplicationTests {

    @Autowired
    private GameService gameService;

    @Autowired
    private UsersRepository userRepository;

    @Autowired
    private ChatRoomService chatRoomService;

    @Test
    @DisplayName("동시성 이슈 재현: 100명이 동시에 투표하면 투표가 누락된다.")
    void concurrencyVoteTest() throws InterruptedException {
        // 1. 게임 방 생성 및 유저 준비
        int threadCount = 100;

        // 첫 번째 유저를 방장으로 생성
        String hostLoginId = "user0";
        Users host = Users.builder()
                .userLoginId(hostLoginId)
                .nickname("player0")
                .userLoginPassword("pw")
                .userRole(UserRole.USER)
                .build();
        userRepository.save(host);

        // 채팅방 생성 (방장이 생성)
        ChatRoom chatRoom = chatRoomService.createRoom(new CreateRoomRequest("test-room"), hostLoginId);
        String roomId = chatRoom.getRoomId();

        // 나머지 유저 생성 및 채팅방 참여
        for (int i = 1; i < threadCount; i++) {
            String loginId = "user" + i;
            Users users = Users.builder()
                    .userLoginId(loginId)
                    .nickname("player" + i)
                    .userLoginPassword("pw")
                    .userRole(UserRole.USER)
                    .build();
            userRepository.save(users);

            chatRoomService.userJoin(new JoinRoomRequest(roomId, loginId));
        }

        // 게임 시작 (이제 roomId만 전달)
        GameState gameState = gameService.createGame(roomId);
        String gameId = gameState.getGameId();
        gameService.startGame(gameId); // DAY_DISCUSSION
        gameService.advancePhase(gameId); // DAY_VOTING (투표 가능 상태로 변경)

        // 2. 멀티스레드 환경 구성 (100명이 동시에 누름)
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount); // 100명 기다리기

        // 3. 동시 투표 수행
        for (int i = 0; i < threadCount; i++) {
            String voterId = "user" + i;
            executorService.submit(() -> {
                try {
                    // 모든 유저가 0번 유저("user0")에게 투표
                    gameService.vote(gameId, voterId, "user0");
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 스레드가 끝날 때까지 대기

        // 4. 결과 검증 (기대값: 100, 실제값: 84)
        GameState findGameState = gameService.getGameState(gameId);
        int totalVotes = findGameState.getVotes().size();

        System.out.println("==================================================");
        System.out.println("기대 투표 수: " + threadCount);
        System.out.println("실제 저장된 투표 수: " + totalVotes);
        System.out.println("==================================================");

        // 100개가 다 저장되면 테스트 실패(문제가 없는 것), 다르면 성공(문제 재현 성공)
        assertThat(totalVotes).isNotEqualTo(threadCount);
    }
}