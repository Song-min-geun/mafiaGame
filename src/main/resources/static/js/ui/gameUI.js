// Game UI Module

import * as api from '../api/apiService.js';
import * as ws from '../websocket/wsService.js';
import {
    getCurrentUser,
    getCurrentRoom,
    getCurrentRoomName,
    getCurrentGame,
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
        if (currentRoom && roomName) {
            headerCurrentRoom.textContent = roomName;
        } else if (currentRoom) {
            headerCurrentRoom.textContent = currentRoom;
        } else {
            headerCurrentRoom.textContent = '없음';
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
    // Start game button
    if (startGameBtn) {
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
export async function startGame() {
    const currentRoom = getCurrentRoom();
    const state = getState();

    if (!currentRoom || !state.currentRoomInfo) {
        alert('방 정보를 찾을 수 없습니다.');
        return;
    }

    const participants = state.currentRoomInfo.participants || [];
    if (participants.length < 4) {
        alert('게임을 시작하려면 최소 4명이 필요합니다.');
        return;
    }

    const players = participants.map(p => ({
        playerId: p.userId,
        playerName: p.userName
    }));

    const roomName = state.currentRoomInfo.roomName || state.currentRoomName || '';

    try {
        const result = await api.createGame(currentRoom, roomName, players);

        if (!result.success) {
            throw new Error(result.message || '게임 시작 실패');
        }

        console.log('게임 생성 성공:', result.gameId);
    } catch (error) {
        alert(error.message || '게임 시작 중 오류가 발생했습니다.');
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

    // Update based on phase
    hideAllGameUI();

    // 타이머 표시 및 업데이트 (게임 상태 복구 시에도 동작)
    if (game.phaseEndTime) {
        showTimer();
        updateTimerDisplay(game);
    }

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
    // Actually log says arrow button to expand.. 
    // If NOT minimized, arrow should be UP to minimize? Or just always toggle.
    // CSS rotates it.

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
export function showFinalVoteUI(game) {
    const votingArea = document.getElementById('votingArea');
    const votingOptions = document.getElementById('votingOptions');
    const votingDescription = document.getElementById('votingDescription');
    const user = getCurrentUser();

    if (!votingArea || !votingOptions) return;

    // Check if current user is the voted player
    if (game.votedPlayerId === user?.userLoginId) {
        showVotedPlayerInfo();
        return;
    }

    showElement('votingArea');
    votingArea.classList.remove('minimized'); // Always start maximized
    if (votingDescription) {
        votingDescription.textContent = `${game.votedPlayerName || '최다 득표자'}를 처형할까요?`;
    }

    votingOptions.innerHTML = `
        <div class="final-vote-buttons">
            <button class="vote-agree" onclick="window.submitFinalVote(true)">찬성</button>
            <button class="vote-disagree" onclick="window.submitFinalVote(false)">반대</button>
        </div>
    `;

    addToggleBtn(votingArea);
}

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
 * Select night action target
 */
function selectNightTarget(playerId, element) {
    document.querySelectorAll('.night-action-option').forEach(el => el.classList.remove('selected'));
    element.classList.add('selected');
    setSelectedNightActionTarget(playerId);

    // Auto-submit night action
    submitNightAction();

    // Auto-minimize
    const nightActionArea = document.getElementById('nightActionArea');
    if (nightActionArea) nightActionArea.classList.add('minimized');
}

/**
 * Submit night action
 */
export function submitNightAction() {
    const state = getState();
    if (!state.selectedNightActionTarget || !state.currentGameId) return;

    ws.sendNightAction(state.currentGameId, state.selectedNightActionTarget);
    addSystemMessage('밤 액션이 완료되었습니다.');
    setSelectedNightActionTarget(null);
    hideElement('nightActionArea');
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
