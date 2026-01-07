// REST API Service (Facade Pattern)

import { API_ENDPOINTS } from '../config.js';
import { getJwtToken, setJwtToken, setCurrentUser, getCurrentUser } from '../state.js';

/**
 * Generic API request with JWT authentication
 */
async function apiRequest(url, options = {}) {
    const token = getJwtToken();
    const headers = {
        'Content-Type': 'application/json',
        ...options.headers
    };

    if (token) {
        headers['Authorization'] = token;
    }

    return fetch(url, { ...options, headers });
}

/**
 * Login with credentials
 */
export async function login(userLoginId, userLoginPassword) {
    const response = await fetch(API_ENDPOINTS.LOGIN, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userLoginId, userLoginPassword })
    });

    const result = await response.json();
    if (!result.success) {
        throw new Error(result.message || '로그인 실패');
    }

    const token = 'Bearer ' + result.data.token;
    setJwtToken(token);

    // Fetch user info
    const userResponse = await apiRequest(API_ENDPOINTS.USER_ME);
    const userResult = await userResponse.json();

    if (!userResult.success) {
        throw new Error(userResult.message || '사용자 정보 조회 실패');
    }

    setCurrentUser(userResult.data);
    return userResult.data;
}

/**
 * Register new user
 */
export async function register(userLoginId, userLoginPassword, nickname) {
    const response = await fetch(API_ENDPOINTS.REGISTER, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userLoginId, userLoginPassword, nickname })
    });

    const result = await response.json();
    if (!result.success) {
        throw new Error(result.message || '회원가입 실패');
    }

    return result;
}

/**
 * Validate current session and load user info
 */
export async function validateSession() {
    const response = await apiRequest(API_ENDPOINTS.USER_ME);

    if (!response.ok) {
        return false;
    }

    const result = await response.json();
    if (result.success && result.data) {
        setCurrentUser(result.data);
        return result.data;
    }

    return false;
}

/**
 * Fetch all chat rooms
 */
export async function fetchRooms() {
    const response = await apiRequest(API_ENDPOINTS.ROOMS);

    if (response.status === 401) {
        throw new Error('Unauthorized');
    }

    if (!response.ok) {
        throw new Error(`방 목록 로드 실패: ${response.status}`);
    }

    return response.json();
}

/**
 * Create a new chat room
 */
export async function createRoom(roomName) {
    const user = getCurrentUser();
    const response = await apiRequest(API_ENDPOINTS.ROOMS, {
        method: 'POST',
        body: JSON.stringify({
            roomName,
            userId: user.userLoginId
        })
    });

    if (!response.ok) {
        throw new Error('방 생성 실패');
    }

    return response.json();
}

/**
 * Fetch room details
 */
export async function fetchRoomDetails(roomId) {
    const response = await apiRequest(`${API_ENDPOINTS.ROOMS}/${roomId}`);

    if (!response.ok) {
        throw new Error('방 정보 조회 실패');
    }

    return response.json();
}

/**
 * Create a new game
 */
export async function createGame(roomId, players) {
    const response = await apiRequest(API_ENDPOINTS.GAME_CREATE, {
        method: 'POST',
        body: JSON.stringify({ roomId, players })
    });

    const result = await response.json();
    return result;
}

/**
 * Update game time (extend/reduce)
 */
export async function updateGameTime(gameId, playerId, seconds) {
    const response = await apiRequest(API_ENDPOINTS.GAME_UPDATE_TIME, {
        method: 'POST',
        body: JSON.stringify({ gameId, playerId, seconds })
    });

    return response.json();
}
