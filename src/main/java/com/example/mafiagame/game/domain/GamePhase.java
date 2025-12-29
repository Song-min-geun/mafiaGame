package com.example.mafiagame.game.domain;

public enum GamePhase {
    DAY_DISCUSSION, // 낮 대화
    DAY_VOTING, // 낮 투표
    DAY_FINAL_DEFENSE, // 최후의 반론
    DAY_FINAL_VOTING, // 찬성/반대
    NIGHT_ACTION // 밤 액션 (마피아)
}