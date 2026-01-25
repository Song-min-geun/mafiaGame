// Room UI Module

import * as api from '../api/apiService.js';
import * as ws from '../websocket/wsService.js';
import {
    getCurrentUser,
    getCurrentRoom,
    setCurrentRoom,
    setCurrentRoomInfo,
    setAllRooms,
    getState,
    setGameStarted
} from '../state.js';
import { addSystemMessage, clearMessages } from './chatUI.js';
import { updateGameButtons, updateUserInfo } from './gameUI.js';

/**
 * Load rooms from server
 */
export async function loadRooms() {
    try {
        const rooms = await api.fetchRooms();
        setAllRooms(rooms);
        renderRoomList();
    } catch (error) {
        if (error.message === 'Unauthorized') {
            // Handle unauthorized - will trigger logout
            throw error;
        }
        const roomList = document.getElementById('roomList');
        if (roomList) {
            roomList.innerHTML = '<div class="room-item error">방 목록을 불러올 수 없습니다.</div>';
        }
    }
}

/**
 * Render room list with filtering and sorting
 */
export function renderRoomList() {
    const state = getState();
    const hidePlaying = document.getElementById('hidePlayingCheckbox')?.checked || false;
    const sortBy = document.getElementById('roomSortSelect')?.value || 'countDesc';
    const roomList = document.getElementById('roomList');
    const currentRoom = getCurrentRoom();

    if (!roomList) return;

    let displayRooms = [...state.allRooms];

    // Filter


    // Sort
    displayRooms.sort((a, b) => {
        // Current room first
        if (currentRoom) {
            if (a.roomId === currentRoom) return -1;
            if (b.roomId === currentRoom) return 1;
        }

        if (sortBy === 'countDesc') {
            const countA = a.participants?.length || 0;
            const countB = b.participants?.length || 0;
            if (countB !== countA) return countB - countA;
            return (a.roomName || '').localeCompare(b.roomName || '');
        } else if (sortBy === 'nameAsc') {
            return (a.roomName || '').localeCompare(b.roomName || '');
        }
        return 0;
    });

    // Render
    roomList.innerHTML = '';

    if (displayRooms.length === 0) {
        roomList.innerHTML = '<div class="room-item no-rooms">표시할 방이 없습니다.</div>';
        return;
    }

    displayRooms.forEach(room => {
        const roomItem = document.createElement('div');
        roomItem.className = 'room-item';
        const participantCount = room.participantsCount !== undefined ? room.participantsCount : (room.participants?.length || 0);
        const maxPlayers = room.maxPlayers || 8;
        const isCurrentRoom = currentRoom === room.roomId;
        let roomName = room.roomName || `방 ${room.roomId}`;


        roomItem.innerHTML = `
            <div class="room-info">
                <strong class="room-name" title="${roomName}">${roomName}</strong>
                <span class="room-count">${participantCount}/${maxPlayers}</span>
            </div>
            ${isCurrentRoom ? '<span class="current-room-badge">현재 방</span>' : ''}
        `;

        roomItem.onclick = () => window.joinRoom(room.roomId);
        roomList.appendChild(roomItem);
    });
}

/**
 * Create new room
 */
export async function createRoom() {
    const roomName = prompt('방 이름을 입력하세요 (비워두면 자동 생성):');
    if (roomName === null) return; // 취소 버튼만 return

    try {
        const room = await api.createRoom(roomName);

        setCurrentRoom(room.roomId);
        setCurrentRoomInfo(room);
        updateGameButtons();

        const user = getCurrentUser();
        addSystemMessage(`${user.nickname || user.userLoginId}님이 방을 개설하였습니다.`);

        // Ensure WebSocket connection
        await ws.connect();

        // Subscribe to room - handled by app.js wrapper
        // ws.subscribeToRoom(room.roomId, handleRoomMessage);

        updateUserInfo();
        updateGameButtons();
        await loadRooms();

    } catch (error) {
        alert(error.message);
    }
}

/**
 * Join a room
 */
export async function joinRoom(roomId) {
    const currentRoom = getCurrentRoom();

    if (currentRoom === roomId) {
        console.log('이미 해당 방에 참가 중입니다.');
        return;
    }

    if (currentRoom) {
        await leaveRoom();
    }

    try {
        setCurrentRoom(roomId);
        setCurrentRoomInfo({});

        // Subscribe to room - Moved to app.js wrapper
        // ws.subscribeToRoom(roomId, handleRoomMessage);
        clearMessages();

        // Join via WebSocket
        ws.joinRoom(roomId);

        // Fetch room details
        try {
            const roomResponse = await api.fetchRoomDetails(roomId);
            if (roomResponse?.data) {
                setCurrentRoomInfo(roomResponse.data);
                updateGameButtons();
                updateUserInfo();
            }
        } catch (e) {
            console.error('방 정보 조회 실패:', e);
        }

        updateUserInfo();
        await loadRooms();

    } catch (error) {
        alert(error.message);
        setCurrentRoom(null);
    }
}

/**
 * Leave current room
 */
export async function leaveRoom() {
    const currentRoom = getCurrentRoom();
    if (!currentRoom) return;

    // 게임 진행 중이면서 살아있는 플레이어는 방 이탈 차단 (죽은 플레이어는 허용)
    const state = getState();
    if (state.isGameStarted && !state.isPlayerDead) {
        alert('게임이 진행 중입니다. 게임이 끝날 때까지 방을 나갈 수 없습니다.');
        return;
    }

    try {
        ws.leaveRoom(currentRoom);
        ws.unsubscribeFromRoom(getState().currentRoomSubscription);

        setCurrentRoom(null);
        setCurrentRoomInfo(null);
        setGameStarted(false);

        clearMessages();
        updateUserInfo();
        await loadRooms();
        updateGameButtons();

    } catch (error) {
        alert(error.message);
    }
}

/**
 * Handle room messages - delegates to appropriate handlers
 */
export function handleRoomMessage(message) {
    // This will be imported and used by the main app
    // Delegates to chatUI and gameUI based on message type
}

/**
 * Refresh room list manually
 */
export async function refreshRoomList() {
    await loadRooms();
}
