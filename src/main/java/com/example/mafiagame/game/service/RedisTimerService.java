package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GameStatus;
import com.example.mafiagame.game.dto.TimerUpdateMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class RedisTimerService implements TimerService, MessageListener {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public RedisTimerService(@Lazy GameService gameService,
            SimpMessagingTemplate messagingTemplate,
            StringRedisTemplate stringRedisTemplate) {
        this.gameService = gameService;
        this.messagingTemplate = messagingTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String TIMER_KEY_PREFIX = "timer:";
    private static final ThreadLocal<TimerUpdateMessage> messageHolder = ThreadLocal
            .withInitial(TimerUpdateMessage::new);

    @Override
    public void startTimer(String gameId) {
        String key = TIMER_KEY_PREFIX + gameId;
        stringRedisTemplate.opsForValue().set(key, gameId, Duration.ofSeconds(1));
    }

    @Override
    public void stopTimer(String gameId) {
        String key = TIMER_KEY_PREFIX + gameId;
        stringRedisTemplate.delete(key);
    }

    @Override
    public void onMessage(@NonNull Message message, @Nullable byte[] pattern) {
        String expiredKey = new String(message.getBody());

        if (!expiredKey.startsWith(TIMER_KEY_PREFIX)) {
            return;
        }

        String gameId = expiredKey.substring(TIMER_KEY_PREFIX.length());
        processTimer(gameId);
    }

    private void processTimer(String gameId) {
        Game game = gameService.getGame(gameId);
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }

        int remainingTime = game.getRemainingTime();
        if (remainingTime > 0) {
            game.setRemainingTime(remainingTime - 1);
            sendTimerUpdate(game);
            startTimer(gameId);
        } else {
            gameService.advancePhase(gameId);
            Game updatedGame = gameService.getGame(gameId);
            if (updatedGame != null && updatedGame.getStatus() == GameStatus.IN_PROGRESS) {
                startTimer(gameId);
            }
        }
    }

    private void sendTimerUpdate(Game game) {
        try {
            TimerUpdateMessage message = messageHolder.get();
            message.updateFrom(game);
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
        } catch (Exception e) {
            log.error("타이머 업데이트 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
}
