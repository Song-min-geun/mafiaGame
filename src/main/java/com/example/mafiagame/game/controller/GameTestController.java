package com.example.mafiagame.game.controller;

import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.game.service.SchedulerTimerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "GameTestController", description = "게임 테스트용 API (nGrinder)")
@RestController
@RequestMapping("/api/games/test")
@RequiredArgsConstructor
@Slf4j
public class GameTestController {

    private final GameService gameService;
    private final SchedulerTimerService timerService;

    @PostMapping("/{gameId}/advance")
    @Operation(summary = "강제 페이즈 진행", description = "[테스트] 게임 페이즈를 강제로 다음으로 진행시킵니다.")
    public void advancePhase(@PathVariable String gameId) {
        log.info("[테스트] 강제 페이즈 진행 요청: gameId={}", gameId);
        gameService.advancePhase(gameId);
    }

    @PostMapping("/timer/start")
    @Operation(summary = "타이머 시작 (Direct Param)", description = "[테스트] 타이머를 시작합니다 (Direct Param 방식).")
    public void startTimer(@RequestBody Map<String, Object> payload) {
        String gameId = (String) payload.get("gameId");
        Long phaseEndTime = ((Number) payload.get("phaseEndTime")).longValue();

        log.info("[테스트] 타이머 시작 요청 (Direct Param): gameId={}, endTime={}", gameId, phaseEndTime);
        timerService.startTimer(gameId, phaseEndTime);
    }

    @PostMapping("/timer/start-legacy")
    @Operation(summary = "타이머 시작 (Legacy)", description = "[테스트] 타이머를 시작합니다 (Legacy 방식).")
    public void startTimerLegacy(@RequestBody Map<String, Object> payload) {
        String gameId = (String) payload.get("gameId");

        log.info("[테스트] 타이머 시작 요청 (Legacy): gameId={}", gameId);
        timerService.startTimer(gameId);
    }
}
