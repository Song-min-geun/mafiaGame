// Main Application Entry Point
// Mafia Game - Modular Architecture

import { MESSAGE_TYPES } from './config.js';
import {
    initFromStorage,
    getCurrentUser,
    getCurrentRoom,
    getState,
    setCurrentUser,
    setCurrentGame,
    setCurrentRoom,
    setCurrentRoomInfo,
    setCurrentRoomName,
    setGameStarted,
    setJwtToken,
    resetAll
} from './state.js';
import * as api from './api/apiService.js';
import * as ws from './websocket/wsService.js';
import * as suggestionsUI from './ui/suggestionsUI.js';
import * as authUI from './ui/authUI.js';
import * as roomUI from './ui/roomUI.js';
import * as chatUI from './ui/chatUI.js';
import * as gameUI from './ui/gameUI.js';
import * as timerUI from './ui/timerUI.js';
import { hideElement, showElement } from './utils/helpers.js';

// Initialize application on DOM load
document.addEventListener('DOMContentLoaded', async () => {
    console.log('🎮 마피아 게임 초기화 중...');

    // 초기 UI 상태 설정 (로그인 전에는 헤더 정보 숨김)
    hideElement('headerUserInfo');

    // OAuth 로그인 후 토큰 처리
    const urlParams = new URLSearchParams(window.location.search);
    const accessToken = urlParams.get('accessToken');
    const refreshToken = urlParams.get('refreshToken');

    if (accessToken && refreshToken) {
        console.log('🔑 OAuth 토큰 감지, 저장 중...');
        // setJwtToken으로 localStorage와 AppState 모두 업데이트
        const token = 'Bearer ' + accessToken;
        setJwtToken(token);
        localStorage.setItem('refreshToken', refreshToken);

        // URL에서 토큰 파라미터 제거 (깔끔한 URL 유지)
        window.history.replaceState({}, document.title, '/');

        // OAuth 로그인 후 유저 정보 가져오기
        try {
            const userData = await api.validateSession();
            if (userData) {
                console.log('✅ OAuth 로그인 성공:', userData);
                hideElement('loginForm');
                hideElement('registerForm');
                showElement('gameScreen');
                showElement('headerUserInfo'); // 로그인 성공 시 표시
                await ws.connect();
                await initializeApp();
                return;
            }
        } catch (error) {
            console.error('OAuth 세션 초기화 실패:', error);
        }
    }

    // Try to restore session
    if (await authUI.tryRestoreSession()) {
        console.log('✅ 세션 복구 성공');
        showElement('headerUserInfo'); // 세션 복구 성공 시 표시
        await initializeApp();
    } else {
        console.log('❌ 세션 없음 - 로그인 화면 표시');
        hideElement('headerUserInfo'); // 세션 없으면 숨김
    }
});

/**
 * Initialize app after successful login
 */
async function initializeApp() {
    // Subscribe to private messages
    ws.subscribeToPrivateMessages(handlePrivateMessage);

    // Subscribe to lobby updates
    ws.subscribeToLobby(() => roomUI.loadRooms());

    // Load rooms
    await roomUI.loadRooms();

    // URL 파라미터로 전략 선택 (?strategy=localStorage 또는 ?strategy=redis)
    const urlParams = new URLSearchParams(window.location.search);
    const strategy = urlParams.get('strategy') || 'redis'; // 기본값: redis

    console.log(`📊 [성능 테스트] 전략: ${strategy}`);
    const startTime = performance.now();

    if (strategy === 'localStorage') {
        // ========== LocalStorage 방식 ==========
        await restoreFromLocalStorage();
    } else {
        // ========== Redis 방식 (기본) ==========
        await restoreFromRedis();
    }

    const endTime = performance.now();
    const elapsedTime = (endTime - startTime).toFixed(2);
    console.log(`📊 [성능 테스트] ${strategy} 방식 소요 시간: ${elapsedTime}ms`);

    // 브라우저 콘솔에 눈에 띄게 표시
    console.log('%c' + `⏱️ ${strategy.toUpperCase()} 방식: ${elapsedTime}ms`,
        'background: #222; color: #bada55; font-size: 16px; padding: 5px;');

    // Update UI
    gameUI.updateUserInfo();
    gameUI.updateGameButtons();
}

/**
 * LocalStorage 기반 게임 상태 복구
 */
async function restoreFromLocalStorage() {
    console.log('🔧 [LocalStorage] 복구 시작...');

    const state = getState();
    const currentRoom = getCurrentRoom();

    if (!currentRoom) {
        console.log('ℹ️ [LocalStorage] 저장된 방 없음');
        return;
    }

    console.log('🔄 [LocalStorage] 방 재연결:', currentRoom);
    await window.joinRoom(currentRoom);

    // 게임 진행 중이었다면 게임 상태 복구
    if (state.isGameStarted && state.currentGameId) {
        console.log('🎮 [LocalStorage] 게임 상태 복구 시도:', state.currentGameId);
        try {
            const gameState = await api.fetchGameState(currentRoom);
            if (gameState) {
                console.log('✅ [LocalStorage] 게임 상태 복구 성공');
                setCurrentGame(gameState);
                setGameStarted(true);
                gameUI.updateGameUI(gameState);
                chatUI.addSystemMessage('게임에 다시 연결되었습니다. (LocalStorage)');

                // UI 입력창 상태 업데이트
                const user = getCurrentUser();
                chatUI.updateChatInputState(gameState.gamePhase, user?.role);
            } else {
                console.log('ℹ️ [LocalStorage] 진행 중인 게임 없음 - 상태 초기화');
                setGameStarted(false);
                setCurrentGame(null);
            }
        } catch (error) {
            console.error('[LocalStorage] 게임 상태 복구 실패:', error);
            setGameStarted(false);
            setCurrentGame(null);
        }
    }
}

/**
 * Redis 기반 게임 상태 복구
 */
async function restoreFromRedis() {
    console.log('🔧 [Redis] 복구 시작...');

    try {
        const myGame = await api.fetchMyGame();
        if (myGame && myGame.success) {
            console.log('🎮 [Redis] 진행 중인 게임 발견:', myGame);
            const { data: gameState, roomId, roomName } = myGame;

            // 방 재연결
            setCurrentRoom(roomId);
            setCurrentRoomName(roomName);
            console.log('🔄 [Redis] 방 재연결:', roomId, roomName);
            await window.joinRoom(roomId);

            // 현재 플레이어 역할 복구
            const user = getCurrentUser();
            if (user && gameState?.players) {
                const currentPlayer = gameState.players.find(p => p.playerId === user.userLoginId);
                if (currentPlayer && currentPlayer.role) {
                    user.role = currentPlayer.role;
                    setCurrentUser(user);
                    console.log('✅ [Redis] 플레이어 역할 복구:', currentPlayer.role);
                }
            }

            // 게임 상태 복구
            setCurrentGame(gameState);
            setGameStarted(true);
            gameUI.updateGameUI(gameState);
            chatUI.addSystemMessage('게임에 다시 연결되었습니다. (Redis)');

            // UI 입력창 상태 업데이트
            chatUI.updateChatInputState(gameState.gamePhase, user?.role);
        } else {
            console.log('ℹ️ [Redis] 진행 중인 게임 없음 - 로컬 상태 초기화');
            // 서버에 게임이 없으므로 로컬 게임 상태도 확실히 제거
            setGameStarted(false);
            setCurrentGame(null);

            // 방 정보만 복구 (방이 존재하는지 확인 후 접속)
            const currentRoom = getCurrentRoom();
            if (currentRoom) {
                console.log('🔄 [Redis] 이전 방 접속 시도:', currentRoom);
                try {
                    // 방 존재 여부 확인
                    const roomInfo = await api.fetchRoomDetails(currentRoom);
                    if (roomInfo && roomInfo.success) {
                        await window.joinRoom(currentRoom);
                    } else {
                        throw new Error('Room not found');
                    }
                } catch (e) {
                    console.log('⚠️ [Redis] 이전 방이 존재하지 않음 - 정보 삭제:', currentRoom);
                    setCurrentRoom(null);
                    setCurrentRoomName(null);
                    setCurrentRoomInfo(null);
                }
            }
        }
    } catch (error) {
        console.error('[Redis] 게임 상태 확인 실패:', error);
        // 에러 발생 시에도 게임 상태는 초기화
        setGameStarted(false);
        setCurrentGame(null);

        const currentRoom = getCurrentRoom();
        if (currentRoom) {
            console.log('🔄 [Redis] 방 접속 복구 시도:', currentRoom);
            try {
                // 방 존재 여부 확인
                const roomInfo = await api.fetchRoomDetails(currentRoom);
                if (roomInfo && roomInfo.success) {
                    await window.joinRoom(currentRoom);
                } else {
                    throw new Error('Room not found');
                }
            } catch (e) {
                console.log('⚠️ [Redis] 이전 방이 존재하지 않음 (에러) - 정보 삭제:', currentRoom);
                setCurrentRoom(null);
                setCurrentRoomName(null);
                setCurrentRoomInfo(null);
            }
        }
    }
}

/**
 * Handle private messages (role assignments, etc.)
 */
function handlePrivateMessage(message) {
    const user = getCurrentUser();

    switch (message.type) {
        case MESSAGE_TYPES.ROLE_ASSIGNED:
            console.log('역할 할당:', message);
            if (user) {
                user.role = message.role;
                user.roleDescription = message.roleDescription;
                setCurrentUser(user);
            }
            gameUI.updateUserInfo();
            chatUI.addSystemMessage(`당신의 역할: ${message.role} - ${message.roleDescription}`);
            break;

        case 'MAFIA_CHAT':
            // 마피아 채팅 (Private Queue로 수신)
            chatUI.processIncomingMessage(message);
            break;

        case 'PRIVATE_MESSAGE':
            chatUI.addSystemMessage(message.content);
            break;

        case 'AI_SUGGESTION':
            console.log('🤖 AI 추천 수신:', message.suggestions);
            if (message.suggestions && Array.isArray(message.suggestions)) {
                // suggestionsUI.loadSuggestions used to be called, but we have data now.
                // But suggestionsUI doesn't export a setter.
                // Hack: Call updateSuggestionsForPhase? No, that fetches.
                // Correct way: Add 'renderSuggestions' to exports in suggestionsUI.js.
                // FOR NOW: Just call loadSuggestions with role/phase/gameId from current state?
                // But message doesn't have gameId/phase/role explicitly in payload (only suggestions).
                // So, simpler: Use suggestionsUI.callRender(suggestions) -> Need to implement.
                // Or: just force refresh via updateSuggestionsForPhase(getCurrentGame())
                const game = getState().currentGame;
                if (game) {
                    api.fetchSuggestions(game.players.find(p => p.playerId === getCurrentUser().userLoginId).role, game.gamePhase, game.gameId)
                        .then(data => {
                            // wait, fetchSuggestions returns the data.
                            // I need to use suggestionsUI to render it.
                            // suggestionsUI.loadSuggestions calls API and renders.
                            // So calling loadSuggestions is enough!
                            const user = getCurrentUser();
                            suggestionsUI.loadSuggestions(user.role, game.gamePhase, game.gameId);
                        });
                }
            }
            break;

        case 'ERROR':
            alert(message.content);
            break;

        default:
            console.log('알 수 없는 개인 메시지:', message);
            if (message.content) {
                chatUI.addSystemMessage(message.content);
            }
    }
}

/**
 * Handle room messages
 */
function handleRoomMessage(chatMessage) {
    const user = getCurrentUser();
    const state = getState();

    switch (chatMessage.type) {
        case 'USER_JOINED':
            if (chatMessage.data?.room) {
                setCurrentRoomInfo(chatMessage.data.room);
                gameUI.updateGameButtons();
            }
            chatUI.addMessage(chatMessage, 'system');
            break;

        case 'USER_LEFT':
            if (chatMessage.data?.room) {
                setCurrentRoomInfo(chatMessage.data.room);
            }
            gameUI.updateUserInfo();
            gameUI.updateGameButtons();
            chatUI.addMessage(chatMessage, 'system');
            break;

        case MESSAGE_TYPES.GAME_START:
            if (!chatMessage.game) {
                console.error('GAME_START 메시지에 game 객체 없음');
                return;
            }
            gameUI.handleGameStart(chatMessage.game);
            // 게임 시작 시 Phase 업데이트에 따라 입력창 검사
            chatUI.updateChatInputState(chatMessage.game.gamePhase, user?.role);
            break;

        case MESSAGE_TYPES.PHASE_SWITCHED:
            if (chatMessage.game?.gameId === state.currentGameId) {
                gameUI.handlePhaseSwitch(chatMessage.game);
                chatUI.updateChatInputState(chatMessage.game.gamePhase, user?.role);
            }
            break;

        case MESSAGE_TYPES.TIMER_UPDATE:
            timerUI.handleTimerUpdate(chatMessage);
            break;

        case MESSAGE_TYPES.GAME_ENDED:
            gameUI.handleGameEnd(chatMessage.winner);
            // 게임 종료 시 입력창 활성화
            chatUI.updateChatInputState('GAME_END', user?.role);
            break;

        case MESSAGE_TYPES.VOTE_RESULT_UPDATE:
            if (chatMessage.gameId === state.currentGameId) {
                const game = state.currentGame;
                if (game) {
                    game.players = chatMessage.players;
                    if (chatMessage.eliminatedPlayerId) {
                        game.votedPlayerId = chatMessage.eliminatedPlayerId;
                        game.votedPlayerName = chatMessage.eliminatedPlayerName;
                    }
                    gameUI.updateGameUI(game);
                }
            }
            break;

        case 'CHAT':
            const messageType = chatMessage.senderId === user?.userLoginId ? 'self' : 'other';
            chatUI.addMessage(chatMessage, messageType);
            break;

        case 'SYSTEM':
        case 'ROOM_CREATED':
            chatUI.addMessage(chatMessage, 'system');
            break;

        case 'ROLE_DISTRIBUTION':
            const roleCounts = chatMessage.rolecounts;
            let text = '역할 분포: ';
            if (roleCounts.MAFIA > 0) text += `마피아 ${roleCounts.MAFIA}명 `;
            if (roleCounts.DOCTOR > 0) text += `의사 ${roleCounts.DOCTOR}명 `;
            if (roleCounts.POLICE > 0) text += `경찰 ${roleCounts.POLICE}명 `;
            if (roleCounts.CITIZEN > 0) text += `시민 ${roleCounts.CITIZEN}명`;
            chatUI.addSystemMessage(text);
            break;

        case 'PRIVATE_MESSAGE':
            if (chatMessage.messageType === 'POLICE_INVESTIGATION') {
                // 경찰 조사 결과 강조 표시
                chatUI.addSystemMessage(`🕵️‍♀️ ${chatMessage.content}`);
                // 밤 액션 UI에도 결과 표시 시도
                const nightActionDesc = document.getElementById('nightActionDescription');
                if (nightActionDesc) {
                    nightActionDesc.textContent = chatMessage.content;
                    nightActionDesc.style.color = '#f1c40f'; // Gold color
                }
            } else {
                chatUI.addSystemMessage(chatMessage.content);
            }
            break;

        case 'TIME_EXTEND':
        case 'TIME_REDUCE':
            if (chatMessage.gameId === state.currentGameId) {
                const game = state.currentGame;
                if (game) {
                    game.remainingTime = chatMessage.remainingTime;
                    timerUI.updateTimerDisplay(game);
                }
                const action = chatMessage.type === 'TIME_EXTEND' ? '연장' : '단축';
            }
            break;

        default:
            if (chatMessage.senderId === 'SYSTEM') {
                chatUI.addMessage(chatMessage, 'system');
            } else {
                const msgType = chatMessage.senderId === user?.userLoginId ? 'self' : 'other';
                chatUI.addMessage(chatMessage, msgType);
            }
    }
}

// ===== Global Functions (for HTML onclick handlers) =====

window.login = async function () {
    const success = await authUI.handleLogin();
    if (success) {
        showElement('headerUserInfo'); // 로그인 성공 시 헤더 표시
        await initializeApp();
    }
};

window.register = authUI.handleRegister;
window.logout = function () {
    authUI.handleLogout();
    hideElement('headerUserInfo'); // 로그아웃 시 헤더 숨김
};
window.showLogin = authUI.showLoginForm;
window.showRegister = authUI.showRegisterForm;
window.checkPasswordMatch = authUI.checkPasswordMatch;

window.createRoom = roomUI.createRoom;
window.joinRoom = roomUI.joinRoom;
window.leaveRoom = roomUI.leaveRoom;
window.refreshRoomList = roomUI.refreshRoomList;
window.filterAndSortRooms = roomUI.renderRoomList;

window.sendMessage = function () {
    const currentRoom = getCurrentRoom();
    if (!currentRoom) return;

    chatUI.handleSendMessage((content) => {
        ws.sendChatMessage(currentRoom, content);
    });
};

window.handleKeyPress = function (event) {
    chatUI.handleKeyPress(event, window.sendMessage);
};

window.startGame = gameUI.startGame;
window.submitVote = gameUI.submitVote;
window.submitNightAction = gameUI.submitNightAction;

window.updateTime = timerUI.updateTime;

// Subscribe to room when joining
// Override global functions with subscription logic
window.joinRoom = async function (roomId) {
    ws.subscribeToRoom(roomId, handleRoomMessage);
    await roomUI.joinRoom(roomId);
};

window.createRoom = async function () {
    await roomUI.createRoom();
    const currentRoom = getCurrentRoom();
    if (currentRoom) {
        ws.subscribeToRoom(currentRoom, handleRoomMessage);
    }
};

console.log('🎮 마피아 게임 모듈 로드 완료');