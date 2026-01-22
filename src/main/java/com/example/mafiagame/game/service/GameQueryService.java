package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePlayerState;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.GameStatus;
import com.example.mafiagame.game.domain.PlayerRole;
import com.example.mafiagame.game.repository.GameRepository;
import com.example.mafiagame.game.repository.GameStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Set;

/**
 * 게임 조회 전용 서비스
 * - Redis/DB에서 게임 상태 조회
 * - 플레이어 검색
 * - 역할 설명 조회
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameQueryService {

    private final GameRepository gameRepository;
    private final GameStateRepository gameStateRepository;

    // ================= 게임 상태 조회 =================

    /**
     * Redis에서 게임 상태 조회
     */
    public GameState getGameState(String gameId) {
        return gameStateRepository.findById(gameId).orElse(null);
    }

    /**
     * DB에서 게임 조회
     */
    @Transactional(readOnly = true)
    public Game getGame(String gameId) {
        return gameRepository.findById(gameId).orElse(null);
    }

    /**
     * 방 ID로 진행 중인 게임 조회
     */
    @Transactional(readOnly = true)
    public Game getGameByRoomId(String roomId) {
        return gameRepository.findFirstByRoomIdAndStatusOrderByStartTimeDesc(roomId, GameStatus.IN_PROGRESS)
                .orElse(null);
    }

    /**
     * 플레이어 ID로 참여 중인 게임 조회 (Redis)
     */
    public GameState getGameByPlayerId(String playerId) {
        return gameStateRepository.findByPlayerId(playerId).orElse(null);
    }

    /**
     * 활성 게임 ID 목록 조회
     */
    public Set<String> getActiveGameIds() {
        return Collections.emptySet();
    }

    // ================= 플레이어 검색 =================

    /**
     * 게임에서 플레이어 검색
     */
    public GamePlayerState findPlayerById(GameState gameState, String playerId) {
        if (gameState == null || gameState.getPlayers() == null) {
            return null;
        }
        return gameState.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 게임에서 살아있는 플레이어 검색
     */
    public GamePlayerState findActivePlayerById(GameState gameState, String playerId) {
        GamePlayerState player = findPlayerById(gameState, playerId);
        return (player != null && player.isAlive()) ? player : null;
    }

    // ================= 역할 정보 =================

    /**
     * 역할 설명 조회
     */
    public String getRoleDescription(PlayerRole role) {
        return switch (role) {
            case MAFIA -> "밤에 한 명을 지목하여 제거할 수 있습니다.";
            case POLICE -> "밤에 한 명을 지목하여 마피아인지 확인할 수 있습니다.";
            case DOCTOR -> "밤에 한 명을 지목하여 마피아의 공격으로부터 보호할 수 있습니다.";
            case CITIZEN -> "특별한 능력이 없습니다. 추리를 통해 마피아를 찾아내세요.";
        };
    }

    // ================= 게임 상태 저장 =================

    /**
     * 게임 상태 저장 (Redis)
     */
    public void saveGameState(GameState gameState) {
        gameStateRepository.save(gameState);
    }

    /**
     * 게임 저장 (DB)
     */
    @Transactional
    public void saveGame(Game game) {
        gameRepository.save(game);
    }
}
