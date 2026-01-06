// Timer UI Module

import * as api from '../api/apiService.js';
import {
    getCurrentUser,
    getState,
    setTimerInterval,
    setTimeExtensionUsed
} from '../state.js';
import { getPhaseDisplayName, getPhaseEndMs, showElement, hideElement } from '../utils/helpers.js';
import { addSystemMessage } from './chatUI.js';
import { GAME_PHASES } from '../config.js';

/**
 * Update timer display
 */
export function updateTimerDisplay(game) {
    const timerLabel = document.getElementById('timerLabel');
    const timerCountdown = document.getElementById('timerCountdown');
    const extendButtons = document.querySelectorAll('.timer-controls button');
    const state = getState();

    console.log('updateTimerDisplay called, isGameStarted:', state.isGameStarted, 'game:', game);

    if (!state.currentGame) {
        console.log('Game not found, hiding timer');
        hideTimer();
        return;
    }

    if (timerLabel && timerCountdown) {
        // Update phase label
        timerLabel.textContent = getPhaseDisplayName(game.gamePhase, game.currentPhase);

        // Clear existing timer before setting new one
        if (state.timerInterval) {
            clearInterval(state.timerInterval);
        }

        const endTimeValue = game.phaseEndTime;
        console.log('phaseEndTime:', endTimeValue);

        if (!endTimeValue) {
            timerCountdown.textContent = '-';
        } else {
            // phaseEndTime is now epoch millis (Long) from backend
            const endTime = getPhaseEndMs(endTimeValue);
            console.log('endTime (ms):', endTime, 'now:', Date.now());

            function updateRemaining() {
                const now = Date.now();
                const diff = endTime - now;
                const seconds = Math.ceil(diff / 1000);

                console.log('Timer tick: seconds remaining =', seconds);

                if (seconds >= 0) {
                    timerCountdown.textContent = seconds;

                    // Warning state (10 seconds or less)
                    if (seconds <= 10) {
                        timerCountdown.classList.add('warning');
                    } else {
                        timerCountdown.classList.remove('warning');
                    }
                } else {
                    timerCountdown.textContent = '0';
                    if (state.timerInterval) {
                        clearInterval(state.timerInterval);
                        state.timerInterval = null;
                    }
                }
            }

            updateRemaining(); // Initial update
            const intervalId = setInterval(updateRemaining, 1000);

            // Store interval ID directly in state (don't use setter which clears first)
            state.timerInterval = intervalId;
            console.log('Timer interval started:', intervalId);
        }

        // Show time control buttons
        extendButtons.forEach(button => {
            button.style.display = 'inline-block';
        });

        // Calculate remaining time for button enable/disable
        const phaseEndMs = getPhaseEndMs(game.phaseEndTime);
        const currentRemaining = phaseEndMs ? Math.ceil((phaseEndMs - Date.now()) / 1000) : 0;
        const isTimeControllablePhase = game.gamePhase === GAME_PHASES.DAY_DISCUSSION;
        const canExtend = isTimeControllablePhase && !state.timeExtensionUsed && currentRemaining > 0;

        extendButtons.forEach(button => {
            button.disabled = !canExtend;
        });

        // Mark extension as used for non-controllable phases
        if (!isTimeControllablePhase) {
            setTimeExtensionUsed(true);
        }
    }
    // Note: Do NOT call updateGameUI here - it causes infinite loop
}

/**
 * Update time (extend/reduce)
 */
export async function updateTime(seconds) {
    const state = getState();
    const user = getCurrentUser();

    // Check phase strictly
    if (state.currentGame?.gamePhase !== GAME_PHASES.DAY_DISCUSSION) {
        alert('토론 시간에만 시간을 조절할 수 있습니다.');
        return;
    }

    if (state.timeExtensionUsed) {
        alert('이번 페이즈에서는 이미 시간 조절을 사용했습니다.');
        return;
    }

    if (!state.currentGameId || !user || !state.currentGame) {
        alert('게임 정보를 찾을 수 없습니다.');
        return;
    }

    // Disable buttons immediately
    setTimeExtensionUsed(true);
    const extendBtn = document.getElementById('extendTimeBtn');
    const reduceBtn = document.getElementById('reduceTimeBtn');
    if (extendBtn) extendBtn.disabled = true;
    if (reduceBtn) reduceBtn.disabled = true;

    try {
        const result = await api.updateGameTime(state.currentGameId, user.userLoginId, seconds);

        if (!result.success) {
            throw new Error(result.message || '시간 조절 실패');
        }

        const action = seconds > 0 ? '연장' : '단축';
        addSystemMessage(`⏰ 시간을 ${Math.abs(seconds)}초 ${action}했습니다.`);

    } catch (error) {
        // Re-enable buttons on error
        setTimeExtensionUsed(false);
        if (extendBtn) extendBtn.disabled = false;
        if (reduceBtn) reduceBtn.disabled = false;

        alert(error.message || '시간 조절 중 오류가 발생했습니다.');
    }
}

/**
 * Handle timer update from server
 */
export function handleTimerUpdate(data) {
    const state = getState();

    if (data.gameId === state.currentGameId) {
        const game = state.currentGame;
        if (game) {
            // Update phaseEndTime from server (critical for time extension/reduction)
            if (data.phaseEndTime) {
                game.phaseEndTime = data.phaseEndTime;
            }
            game.gamePhase = data.gamePhase;
            game.currentPhase = data.currentPhase;
            updateTimerDisplay(game);
        }

        if (data.systemMessage) {
            addSystemMessage(data.systemMessage);
        }
    }
}

/**
 * Hide timer
 */
export function hideTimer() {
    const gameTimer = document.getElementById('gameTimer');
    if (gameTimer) {
        gameTimer.style.display = 'none';
    }
    setTimerInterval(null);
}

/**
 * Show timer
 */
export function showTimer() {
    showElement('gameTimer');
}

// Make updateTime available globally for onclick
window.updateTime = updateTime;
