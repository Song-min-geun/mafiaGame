// Global application state management (Singleton Pattern)

const AppState = {
    // User state
    currentUser: null,
    jwtToken: null,

    // Room state
    currentRoom: null,
    currentRoomInfo: null,
    allRooms: [],

    // Game state
    currentGame: null,
    currentGameId: null,
    isGameStarted: false,
    isPlayerDead: false,

    // Voting state
    selectedVoteTarget: null,
    selectedNightActionTarget: null,
    timeExtensionUsed: false,

    // Connection state
    stompClient: null,
    currentRoomSubscription: null,

    // Timer state
    timerInterval: null,
    lastRefreshTime: 0
};

// Getters
export function getState() {
    return AppState;
}

export function getCurrentUser() {
    return AppState.currentUser;
}

export function getJwtToken() {
    return AppState.jwtToken;
}

export function getCurrentRoom() {
    return AppState.currentRoom;
}

export function getCurrentGame() {
    return AppState.currentGame;
}

export function getStompClient() {
    return AppState.stompClient;
}

// Setters
export function setCurrentUser(user) {
    AppState.currentUser = user;
    if (user) {
        localStorage.setItem('currentUser', JSON.stringify(user));
    } else {
        localStorage.removeItem('currentUser');
    }
}

export function setJwtToken(token) {
    AppState.jwtToken = token;
    if (token) {
        localStorage.setItem('jwtToken', token);
    } else {
        localStorage.removeItem('jwtToken');
    }
}

export function setCurrentRoom(roomId) {
    AppState.currentRoom = roomId;
    if (roomId) {
        localStorage.setItem('currentRoom', roomId);
    } else {
        localStorage.removeItem('currentRoom');
    }
}

export function setCurrentRoomInfo(roomInfo) {
    AppState.currentRoomInfo = roomInfo;
}

export function setAllRooms(rooms) {
    AppState.allRooms = rooms;
}

export function setCurrentGame(game) {
    AppState.currentGame = game;
    if (game) {
        AppState.currentGameId = game.gameId;
    }
}

export function setGameStarted(started) {
    AppState.isGameStarted = started;
}

export function setStompClient(client) {
    AppState.stompClient = client;
}

export function setCurrentRoomSubscription(subscription) {
    AppState.currentRoomSubscription = subscription;
}

export function setTimerInterval(interval) {
    if (AppState.timerInterval) {
        clearInterval(AppState.timerInterval);
    }
    AppState.timerInterval = interval;
}

export function setTimeExtensionUsed(used) {
    AppState.timeExtensionUsed = used;
}

export function setSelectedVoteTarget(target) {
    AppState.selectedVoteTarget = target;
}

export function setSelectedNightActionTarget(target) {
    AppState.selectedNightActionTarget = target;
}

export function setPlayerDead(dead) {
    AppState.isPlayerDead = dead;
}

// Reset functions
export function resetGameState() {
    AppState.currentGame = null;
    AppState.currentGameId = null;
    AppState.isGameStarted = false;
    AppState.isPlayerDead = false;
    AppState.selectedVoteTarget = null;
    AppState.selectedNightActionTarget = null;
    AppState.timeExtensionUsed = false;
    if (AppState.timerInterval) {
        clearInterval(AppState.timerInterval);
        AppState.timerInterval = null;
    }
}

export function resetAll() {
    resetGameState();
    AppState.currentUser = null;
    AppState.jwtToken = null;
    AppState.currentRoom = null;
    AppState.currentRoomInfo = null;
    AppState.allRooms = [];
    AppState.stompClient = null;
    AppState.currentRoomSubscription = null;
    localStorage.clear();
}

// Initialize from localStorage
export function initFromStorage() {
    const storedToken = localStorage.getItem('jwtToken');
    const storedUser = localStorage.getItem('currentUser');

    if (storedToken && storedUser) {
        AppState.jwtToken = storedToken;
        try {
            AppState.currentUser = JSON.parse(storedUser);
            return true;
        } catch (e) {
            console.error('Failed to parse stored user:', e);
            localStorage.clear();
        }
    }
    if (storedToken && storedUser) {
        AppState.jwtToken = storedToken;
        try {
            AppState.currentUser = JSON.parse(storedUser);
            // Restore room
            const storedRoom = localStorage.getItem('currentRoom');
            if (storedRoom) {
                AppState.currentRoom = storedRoom;
            }
            return true;
        } catch (e) {
            console.error('Failed to parse stored user:', e);
            localStorage.clear();
        }
    }
    return false;
}
