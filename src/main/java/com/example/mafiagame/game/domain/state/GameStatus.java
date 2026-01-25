package com.example.mafiagame.game.domain.state;

public enum GameStatus {
    WAITING, // 게임 시작 전 대기 상태
    IN_PROGRESS, // 게임이 진행 중인 상태
    ENDED // 게임이 종료된 상태
}