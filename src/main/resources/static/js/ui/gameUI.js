// Game UI Module

import * as api from '../api/apiService.js';
import * as ws from '../websocket/wsService.js';
import {
    getCurrentUser,
    getCurrentRoom,
    getCurrentRoomName,
    setCurrentGame,
    setGameStarted,
    getState,
    setSelectedVoteTarget,
    setSelectedNightActionTarget,
    setTimeExtensionUsed,
    resetGameState
} from '../state.js';
import { addSystemMessage } from './chatUI.js';
import { updateTimerDisplay, hideTimer, showTimer } from './timerUI.js';
import { updateSuggestionsForPhase, clearSuggestions } from './suggestionsUI.js';
import { getRoleDisplayName, getPhaseDisplayName, showElement, hideElement } from '../utils/helpers.js';
import { GAME_PHASES } from '../config.js';

/**
 * Update user info in header
 */
export function updateUserInfo() {
    const user = getCurrentUser();
    const currentRoom = getCurrentRoom();
    const currentRoomName = getCurrentRoomName();
    const state = getState();

    const headerUserInfo = document.getElementById('headerUserInfo');
    const headerUserName = document.getElementById('headerUserName');
    const headerCurrentRoom = document.getElementById('headerCurrentRoom');
    const headerUserRole = document.getElementById('headerUserRole');

    if (!user) {
        if (headerUserInfo) headerUserInfo.style.display = 'none';
        return;
    }

    if (headerUserInfo) headerUserInfo.style.display = 'flex';
    if (headerUserName) headerUserName.textContent = user.nickname || user.userLoginId;

    if (headerCurrentRoom) {
        const roomName = state.currentRoomName || state.currentRoomInfo?.roomName;
        if (roomName) {
            headerCurrentRoom.textContent = roomName;
        } else {
            headerCurrentRoom.textContent = currentRoom ? '방 접속 중...' : '없음';
        }
    }

    // Role display
    if (headerUserRole && user.role) {
        headerUserRole.textContent = getRoleDisplayName(user.role);
        headerUserRole.style.display = 'inline';
        headerUserRole.className = `user-role role-${user.role.toLowerCase()}`;
    } else if (headerUserRole) {
        headerUserRole.style.display = 'none';
    }
}

/**
 * Update role display in header based on game player's role
 */
function updateRoleDisplay(currentPlayer) {
    const headerUserRole = document.getElementById('headerUserRole');

    if (!currentPlayer || !currentPlayer.role) {
        if (headerUserRole) headerUserRole.style.display = 'none';
        return;
    }

    const role = currentPlayer.role;
    const roleName = getRoleDisplayName(role);

    // Update header role badge
    if (headerUserRole) {
        headerUserRole.textContent = roleName;
        headerUserRole.style.display = 'inline';
        headerUserRole.className = `user-role role-${role.toLowerCase()}`;
    }
}

/**
 * Update game control buttons
 */
export function updateGameButtons() {
    const state = getState();
    const user = getCurrentUser();
    const currentRoom = getCurrentRoom();

    const startGameBtn = document.getElementById('startGameBtn');
    const leaveRoomBtn = document.getElementById('leaveRoomBtn');
    const createRoomBtn = document.getElementById('createRoomBtn');

    // Leave room button
    if (leaveRoomBtn) {
        leaveRoomBtn.style.display = currentRoom ? 'block' : 'none';
        // 게임 진행 중이면서 살아있는 플레이어만 비활성화 (죽은 플레이어는 활성화)
        const shouldDisable = state.isGameStarted && !state.isPlayerDead;
        leaveRoomBtn.disabled = shouldDisable;
        if (shouldDisable) {
            leaveRoomBtn.title = '게임 진행 중에는 나갈 수 없습니다';
        } else {
            leaveRoomBtn.title = '';
        }
    }

    // Create room button
    if (createRoomBtn) {
        // Hide create button if already in a room
        createRoomBtn.style.display = currentRoom ? 'none' : 'block';
        createRoomBtn.disabled = !!currentRoom;
    }

    // Start game button
    if (startGameBtn) {
        startGameBtn.textContent = '게임 시작';
        const roomInfo = state.currentRoomInfo;
        const isHost = roomInfo?.hostId === user?.userLoginId ||
            roomInfo?.participants?.[0]?.userId === user?.userLoginId;

        // Hide button if not host
        if (!isHost) {
            startGameBtn.style.display = 'none';
        } else {
            startGameBtn.style.display = 'block';

            const participantCount = roomInfo?.participants?.length || 0;
            // Host validates conditions
            const canStart = currentRoom && participantCount >= 4 && !state.isGameStarted;
            startGameBtn.disabled = !canStart;
        }
    }
}

/**
 * Start game
 */
let isCreatingGame = false; // 중복 요청 방지 플래그

export async function startGame() {
    // 중복 요청 방지
    if (isCreatingGame) {
        console.log('게임 생성 중... 중복 요청 무시');
        return;
    }

    const currentRoom = getCurrentRoom();
    const state = getState();
    const startGameBtn = document.getElementById('startGameBtn');

    if (!currentRoom || !state.currentRoomInfo) {
        alert('방 정보를 찾을 수 없습니다.');
        return;
    }

    const participants = state.currentRoomInfo.participants || [];
    if (participants.length < 4) {
        alert('게임을 시작하려면 최소 4명이 필요합니다.');
        return;
    }

    try {
        // 중복 요청 방지 시작
        isCreatingGame = true;
        if (startGameBtn) {
            startGameBtn.disabled = true;
            startGameBtn.textContent = '게임 시작 중...';
        }

        // 이제 roomId만 전달 (백엔드에서 플레이어 정보 직접 조회)
        const result = await api.createGame(currentRoom);

        if (!result.success) {
            throw new Error(result.message || '게임 시작 실패');
        }

        console.log('게임 생성 성공:', result.gameId);
    } catch (error) {
        alert(error.message || '게임 시작 중 오류가 발생했습니다.');
        // 에러 시 버튼 복구
        if (startGameBtn) {
            startGameBtn.disabled = false;
            startGameBtn.textContent = '게임 시작';
        }
    } finally {
        // 일정 시간 후 플래그 해제 (게임 시작 메시지 수신 후 버튼은 숨김 처리됨)
        setTimeout(() => {
            isCreatingGame = false;
        }, 3000);
    }
}

/**
 * Update game UI based on current game state
 */
export function updateGameUI(game) {
    if (!game) return;

    setCurrentGame(game);
    const user = getCurrentUser();

    // Find current player
    const currentPlayer = game.players?.find(p => p.playerId === user?.userLoginId);
    const isAlive = currentPlayer?.alive !== false;

    // Update role in header if player has a role
    updateRoleDisplay(currentPlayer);

    // Update based on phase
    hideAllGameUI();

    // 타이머 표시 및 업데이트 (게임 상태 복구 시에도 동작)
    if (game.phaseEndTime) {
        showTimer();
        updateTimerDisplay(game);
    }

    // 추천 문구 업데이트
    updateSuggestionsForPhase(game);

    if (!isAlive) {
        showDeadPlayerUI();
        return;
    }

    switch (game.gamePhase) {
        case GAME_PHASES.DAY_VOTING:
            showVotingUI(game);
            break;
        case GAME_PHASES.DAY_FINAL_VOTING:
            showFinalVoteUI(game);
            break;
        case GAME_PHASES.NIGHT_ACTION:
            showNightActionUI(game, currentPlayer);
            break;
    }
}

/**
 * Show voting UI
 */
export function showVotingUI(game) {
    const votingArea = document.getElementById('votingArea');
    const votingOptions = document.getElementById('votingOptions');
    const user = getCurrentUser();

    if (!votingArea || !votingOptions) return;

    showElement('votingArea');
    votingArea.classList.remove('minimized'); // Always start maximized
    votingOptions.innerHTML = '';

    console.log('DEBUG: showVotingUI', { game, user });

    const alivePlayers = game.players?.filter(p => {
        const isSelf = p.playerId === user?.userLoginId;
        const isAlive = p.alive;
        console.log(`DEBUG Check Player ${p.playerId}: alive=${isAlive} (${p.alive}), self=${isSelf} (user=${user?.userLoginId}) -> Keep? ${isAlive && !isSelf}`);
        return isAlive && !isSelf;
    }) || [];

    console.log(`DEBUG: Final alivePlayers count: ${alivePlayers.length}`, alivePlayers);

    if (alivePlayers.length === 0) {
        votingOptions.innerHTML = '<div class="no-targets">투표할 대상이 없습니다.</div>';
    }

    alivePlayers.forEach(player => {
        const option = document.createElement('div');
        option.className = 'vote-option';
        option.innerHTML = `
            <span class="player-name">${player.playerName}</span>
        `;
        option.onclick = () => selectVoteTarget(player.playerId, option);
        votingOptions.appendChild(option);
    });

    addToggleBtn(votingArea);
}

function addToggleBtn(areaElement) {
    if (areaElement.querySelector('.ui-toggle-btn')) return;

    const btn = document.createElement('button');
    btn.className = 'ui-toggle-btn';
    btn.innerHTML = '<span class="arrow-chevron"></span>'; // CSS styled chevron

    btn.onclick = (e) => {
        e.stopPropagation();
        areaElement.classList.toggle('minimized');
    };
    areaElement.appendChild(btn);
}

/**
 * Select vote target
 */
function selectVoteTarget(playerId, element) {
    document.querySelectorAll('.vote-option').forEach(el => el.classList.remove('selected'));
    element.classList.add('selected');
    setSelectedVoteTarget(playerId);

    // Auto-submit vote
    submitVote();

    // Auto-minimize
    const votingArea = document.getElementById('votingArea');
    if (votingArea) votingArea.classList.add('minimized');
}

/**
 * Submit vote
 */
export function submitVote() {
    const state = getState();
    if (!state.selectedVoteTarget || !state.currentGameId) return;

    ws.sendVote(state.currentGameId, state.selectedVoteTarget);
    addSystemMessage('투표가 완료되었습니다.');
    setSelectedVoteTarget(null);
}

/**
 * Show final vote UI (agree/disagree)
 */


/**
 * Submit final vote
 */
export function submitFinalVote(vote) {
    const state = getState();
    if (!state.currentGameId) return;

    ws.sendFinalVote(state.currentGameId, vote);
    addSystemMessage('최종 투표가 완료되었습니다.');

    // Auto-minimize
    const votingArea = document.getElementById('votingArea');
    if (votingArea) votingArea.classList.add('minimized');
}

/**
 * Show night action UI
 */
export function showNightActionUI(game, currentPlayer) {
    const nightActionArea = document.getElementById('nightActionArea');
    const nightActionOptions = document.getElementById('nightActionOptions');
    const nightActionTitle = document.getElementById('nightActionTitle');
    const nightActionDescription = document.getElementById('nightActionDescription');

    if (!nightActionArea || !nightActionOptions || !currentPlayer) return;

    const role = currentPlayer.role;

    // Only special roles act at night
    if (!['MAFIA', 'DOCTOR', 'POLICE'].includes(role)) {
        addSystemMessage('밤이 되었습니다. 다음 날을 기다려주세요.');
        return;
    }

    showElement('nightActionArea');

    // Set title and description based on role
    const roleDescriptions = {
        MAFIA: { title: '마피아 액션', desc: '제거할 대상을 선택하세요.' },
        DOCTOR: { title: '의사 액션', desc: '치료할 대상을 선택하세요.' },
        POLICE: { title: '경찰 액션', desc: '조사할 대상을 선택하세요.' }
    };

    const roleInfo = roleDescriptions[role] || { title: '밤 액션', desc: '대상을 선택하세요.' };
    if (nightActionTitle) nightActionTitle.textContent = roleInfo.title;
    if (nightActionDescription) nightActionDescription.textContent = roleInfo.desc;

    nightActionOptions.innerHTML = '';

    const targets = game.players?.filter(p => {
        if (!p.alive) return false;
        if (role === 'MAFIA' && p.role === 'MAFIA') return false;
        return true;
    }) || [];

    targets.forEach(player => {
        const option = document.createElement('div');
        option.className = 'night-action-option';
        option.innerHTML = `<span class="player-name">${player.playerName}</span>`;
        option.onclick = () => selectNightTarget(player.playerId, option);
        nightActionOptions.appendChild(option);
    });
}

/**
 * Show final vote UI (Agree/Disagree)
 */
export function showFinalVoteUI(game) {
    const votingArea = document.getElementById('votingArea');
    const votingOptions = document.getElementById('votingOptions');
    const votingDescription = document.getElementById('votingDescription');
    const user = getCurrentUser();

    if (!votingArea || !votingOptions) return;

    // Show area
    showElement('votingArea');
    votingArea.classList.remove('minimized');

    // Update description
    if (votingDescription) {
        votingDescription.textContent = `${game.votedPlayerName || '대상'}님을 처형하시겠습니까?`;
    }

    // Verify if current user is the voted player (Accused)
    if (user && user.userLoginId === game.votedPlayerId) {
        votingOptions.innerHTML = '<div class="vote-status-message">당신은 최후 변론 중입니다.<br>투표 권한이 없습니다.</div>';
        return;
    }

    votingOptions.innerHTML = '';

    // Create Agree/Disagree options
    const options = [
        { id: 'AGREE', text: '찬성', class: 'vote-agree' },
        { id: 'DISAGREE', text: '반대', class: 'vote-disagree' }
    ];

    options.forEach(opt => {
        const option = document.createElement('div');
        option.className = `vote-option ${opt.class}`;
        option.innerHTML = `<span class="vote-text">${opt.text}</span>`;
        option.onclick = () => selectFinalVoteTarget(opt.id, option);
        votingOptions.appendChild(option);
    });
}

function selectFinalVoteTarget(choice, element) {
    document.querySelectorAll('.vote-option').forEach(el => el.classList.remove('selected'));
    element.classList.add('selected');

    // Auto submit for final vote
    ws.sendFinalVote(getState().currentGameId, choice);

    // Minimize after selection
    const votingArea = document.getElementById('votingArea');
    if (votingArea) votingArea.classList.add('minimized');
}



/**
 * Select night action target
 */
function selectNightTarget(playerId, element) {
    document.querySelectorAll('.night-action-option').forEach(el => el.classList.remove('selected'));
    element.classList.add('selected');
    setSelectedNightActionTarget(playerId);

    // Auto-submit night action but allow modification (don't minimize strictly or allow reopen)
    submitNightAction();

    // Feedback to user
    const nightActionDescription = document.getElementById('nightActionDescription');
    if (nightActionDescription) {
        nightActionDescription.textContent = "선택 완료. 변경하려면 다른 대상을 클릭하세요.";
        nightActionDescription.style.color = "#4cd137";
    }
}

/**
 * Submit night action
 */
export function submitNightAction() {
    const state = getState();
    if (!state.selectedNightActionTarget || !state.currentGameId) return;

    ws.sendNightAction(state.currentGameId, state.selectedNightActionTarget);
    addSystemMessage('밤 액션이 완료되었습니다.');

    // Don't hide or clear selection so user can modify it
    // setSelectedNightActionTarget(null);
    // hideElement('nightActionArea');
}

/**
 * Show voted player info
 */
function showVotedPlayerInfo() {
    const votedPlayerInfo = document.getElementById('votedPlayerInfo');
    if (votedPlayerInfo) {
        showElement('votedPlayerInfo');
    }
}

/**
 * Show dead player UI
 */
function showDeadPlayerUI() {
    addSystemMessage('당신은 사망했습니다. 관전 모드입니다.');
    // Could hide action areas and show spectator UI
}

/**
 * Hide all game UI elements
 */
export function hideAllGameUI() {
    hideElement('votingArea');
    hideElement('nightActionArea');
    hideElement('votedPlayerInfo');
}

/**
 * Handle game end
 */
export function handleGameEnd(winner) {
    const winnerTeam = winner === 'MAFIA' ? '마피아 팀' : '시민 팀';
    addSystemMessage(`🎉 게임 종료! ${winnerTeam}의 승리입니다!`);

    hideAllGameUI();
    hideTimer();
    clearSuggestions();
    resetGameState();
    updateGameButtons();
}

/**
 * Handle game start
 */
export function handleGameStart(game) {
    setCurrentGame(game);
    setGameStarted(true);

    addSystemMessage('게임이 시작되었습니다.');

    updateGameUI(game);
    updateGameButtons();
    updateTimerDisplay(game);  // Start the timer

    showElement('gameTimer');
}

/**
 * Handle phase switch
 */
export function handlePhaseSwitch(game) {
    setCurrentGame(game);
    setTimeExtensionUsed(false);

    updateGameUI(game);
    updateTimerDisplay(game);

    // Enable time extension buttons for new phase
    const extendBtn = document.getElementById('extendTimeBtn');
    const reduceBtn = document.getElementById('reduceTimeBtn');
    if (extendBtn) extendBtn.disabled = false;
    if (reduceBtn) reduceBtn.disabled = false;
}

// Make submitFinalVote available globally for inline onclick
window.submitFinalVote = submitFinalVote;
