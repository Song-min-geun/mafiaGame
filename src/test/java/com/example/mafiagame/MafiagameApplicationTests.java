package com.example.mafiagame;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePlayer;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MafiagameApplicationTests {

    @Autowired
    private GameService gameService;

    @Autowired
    private UserRepository userRepository; // 더미 유저 생성을 위해 필요

    @Test
    @DisplayName("동시성 이슈 재현: 100명이 동시에 투표하면 투표가 누락된다.")
    void concurrencyVoteTest() throws InterruptedException {
        // 1. 게임 방 생성 및 유저 준비
        String roomId = "test-room";
        List<GamePlayer> players = new ArrayList<>();

        // 투표할 유저 100명 생성 (테스트용)
        int threadCount = 100;
        for (int i = 0; i < threadCount; i++) {
            // 실제 DB에 없어도 GamePlayer 객체만 있으면 됨
            User user = User.builder().userLoginId("user" + i).nickname("player" + i).build();
            GamePlayer player = GamePlayer.builder().user(user).isAlive(true).build();
            players.add(player);
        }

        // 게임 생성 및 시작
        Game game = gameService.createGame(roomId, players);
        String gameId = game.getGameId();
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