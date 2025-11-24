package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GameStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameTimerService {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedRate = 1000)
    public void updateAllGameTimers() {
        // GameService에서 활성 게임 목록의 ID를 가져옵니다.
        Set<String> activeGameIds = gameService.getActiveGameIds();
        if (activeGameIds.isEmpty()) {
            return;
        }

        // keySet을 복사하여 사용 (ConcurrentModificationException 방지)
        for (String gameId : Set.copyOf(activeGameIds)) {
            try {
                updateGameTimer(gameId);
            } catch (Exception e) {
                log.error("게임 타이머 업데이트 중 오류 발생: {}", gameId, e);
            }
        }
    }

    private void updateGameTimer(String gameId) {
        Game game = gameService.getGame(gameId);
        // 게임이 없거나, 진행중이 아니면 타이머 로직을 실행하지 않습니다.
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }

        int remainingTime = game.getRemainingTime();
                    if (remainingTime > 0) {
                        game.setRemainingTime(remainingTime - 1);
                        sendTimerUpdate(game);
                    } else {            // 시간이 종료되면 GameService에 단계 전환을 위임합니다.
            log.info("시간 종료! GameService에 페이즈 전환 위임: {}", gameId);
            gameService.advancePhase(gameId);
        }
    }

    private void sendTimerUpdate(Game game) {
        try {
            Map<String, Object> message = Map.of(
                "type", "TIMER_UPDATE",
                "gameId", game.getGameId(),
                "roomId", game.getRoomId(),
                "remainingTime", game.getRemainingTime(),
                "gamePhase", game.getGamePhase().toString(),
                "currentPhase", game.getCurrentPhase(),
                "isDay", game.isDay()
            );
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
        } catch (Exception e) {
            log.error("타이머 업데이트 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }

}