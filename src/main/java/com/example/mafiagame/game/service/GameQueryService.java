package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.entity.Game;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.repository.GameRepository;
import com.example.mafiagame.game.repository.GameStateRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;

/**
 * 게임 상태 조회 전용 서비스
 * GameService에서 조회 메서드를 분리하여 순환 참조를 줄이고 단일 책임 원칙을 적용합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameQueryService {

    private final GameRepository gameRepository;
    private final GameStateRepository gameStateRepository;

    public GameState getGameState(String gameId) {
        return gameStateRepository.findById(gameId).orElse(null);
    }

    public Game getGame(String gameId) {
        return gameRepository.findById(gameId).orElse(null);
    }

    /**
     * 방 ID로 진행 중인 게임 조회
     */
    public Game getGameByRoomId(String roomId) {
        return gameRepository.findFirstByRoomIdAndStatusOrderByStartTimeDesc(roomId, GameStatus.IN_PROGRESS)
                .orElse(null);
    }

    /**
     * 플레이어가 참여 중인 게임 조회 (Redis)
     */
    public GameState getGameByPlayerId(String playerId) {
        return gameStateRepository.findByPlayerId(playerId).orElse(null);
    }

    /**
     * 해당 방에 진행 중인 게임이 있는지 확인
     */
    public boolean hasActiveGame(String roomId) {
        return getActiveGameByRoomId(roomId) != null;
    }

    /**
     * 해당 방의 진행 중인 게임 상태 조회 (Redis)
     */
    public GameState getActiveGameByRoomId(String roomId) {
        return gameStateRepository.findByRoomId(roomId)
                .filter(gs -> gs.getStatus() == GameStatus.IN_PROGRESS)
                .orElse(null);
    }

    /**
     * 투표 시간 연장 가능 여부
     */
    public boolean canExtendVotingTime(String playerId) {
        GameState gameState = getGameByPlayerId(playerId);
        if (gameState == null)
            return false;
        return gameState.canExtendVotingTime(playerId);
    }

    /**
     * 활성 게임 ID 목록 (미구현)
     */
    public Set<String> getActiveGameIds() {
        return Collections.emptySet();
    }

    /**
     * 플레이어가 방을 떠날 수 있는지 확인
     */
    public boolean canPlayerLeaveRoom(String roomId, String userId) {
        Game game = getGameByRoomId(roomId);
        if (game == null)
            return true;

        GameState gameState = getGameState(game.getGameId());
        if (gameState == null)
            return true;

        return gameState.canPlayerLeave(userId);
    }

    /**
     * 플레이어가 채팅할 수 있는지 확인
     */
    public boolean canPlayerChat(String roomId, String playerId) {
        Game game = getGameByRoomId(roomId);
        if (game == null)
            return true;

        GameState gameState = getGameState(game.getGameId());
        if (gameState == null)
            return true;

        return gameState.canPlayerChat(playerId);
    }
}
