package com.example.mafiagame.game.domain;

public enum GameStatus {
    WAITING,    // 대기 중 (플레이어 모집)
    STARTING,   // 게임 시작 준비
    NIGHT,      // 밤 (마피아 활동 시간)
    DAY,        // 낮 (투표 시간)
    VOTING,     // 투표 중
    ENDED       // 게임 종료
}
