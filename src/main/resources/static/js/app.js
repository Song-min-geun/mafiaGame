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
    setCurrentRoomInfo,
    setGameStarted,
    setJwtToken,
    resetAll
} from './state.js';
import * as api from './api/apiService.js';
import * as ws from './websocket/wsService.js';
import * as authUI from './ui/authUI.js';
import * as roomUI from './ui/roomUI.js';
import * as chatUI from './ui/chatUI.js';
import * as gameUI from './ui/gameUI.js';
import * as timerUI from './ui/timerUI.js';
import { hideElement, showElement } from './utils/helpers.js';

// Initialize application on DOM load
document.addEventListener('DOMContentLoaded', async () => {
    console.log('🎮 마피아 게임 초기화 중...');

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
        await initializeApp();
    } else {
        console.log('❌ 세션 없음 - 로그인 화면 표시');
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

    // Restore room connection if exists
    const currentRoom = getCurrentRoom();
    if (currentRoom) {
        console.log('🔄 이전 방 접속 복구:', currentRoom);
        await window.joinRoom(currentRoom);
    }

    // Update UI
    gameUI.updateUserInfo();
    gameUI.updateGameButtons();
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

        case 'PRIVATE_MESSAGE':
            chatUI.addSystemMessage(message.content);
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
            break;

        case MESSAGE_TYPES.PHASE_SWITCHED:
            if (chatMessage.game?.gameId === state.currentGameId) {
                gameUI.handlePhaseSwitch(chatMessage.game);
            }
            break;

        case MESSAGE_TYPES.TIMER_UPDATE:
            timerUI.handleTimerUpdate(chatMessage);
            break;

        case MESSAGE_TYPES.GAME_ENDED:
            gameUI.handleGameEnd(chatMessage.winner);
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

        case 'TIME_EXTEND':
        case 'TIME_REDUCE':
            if (chatMessage.gameId === state.currentGameId) {
                const game = state.currentGame;
                if (game) {
                    game.remainingTime = chatMessage.remainingTime;
                    timerUI.updateTimerDisplay(game);
                }
                const action = chatMessage.type === 'TIME_EXTEND' ? '연장' : '단축';
                chatUI.addSystemMessage(`⏰ ${chatMessage.playerName}님이 시간을 ${chatMessage.seconds}초 ${action}했습니다.`);
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
        await initializeApp();
    }
};

window.register = authUI.handleRegister;
window.logout = authUI.handleLogout;
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