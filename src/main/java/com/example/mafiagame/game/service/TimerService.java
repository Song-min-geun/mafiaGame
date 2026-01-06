package com.example.mafiagame.game.service;

/**
 * 타이머 서비스 인터페이스
 * GameTimerService와 RedisTimerService가 공통으로 구현
 */
public interface TimerService {
    void startTimer(String gameId);

    void stopTimer(String gameId);
}
