// Configuration constants for the Mafia Game application

export const API_ENDPOINTS = {
    LOGIN: '/api/users/login',
    REGISTER: '/api/users/register',
    USER_ME: '/api/users/me',
    ROOMS: '/api/chat/rooms',
    GAME_CREATE: '/api/games/create',
    GAME_UPDATE_TIME: '/api/games/update-time',
    GAME_STATE: '/api/games/state',
    GAME_SUGGESTIONS: '/api/games/suggestions'
};

export const WS_TOPICS = {
    PRIVATE: (userId) => `/topic/private/${userId}`,
    ROOM: (roomId) => `/topic/room.${roomId}`,
    LOBBY: '/topic/rooms'
};

export const WS_DESTINATIONS = {
    ROOM_JOIN: '/app/room.join',
    ROOM_LEAVE: '/app/room.leave',
    CHAT_SEND: '/app/chat.sendMessage',
    GAME_VOTE: '/app/game.vote',
    GAME_FINAL_VOTE: '/app/game.finalVote',
    GAME_NIGHT_ACTION: '/app/game.nightAction'
};

export const GAME_PHASES = {
    DAY_DISCUSSION: 'DAY_DISCUSSION',
    DAY_VOTING: 'DAY_VOTING',
    DAY_FINAL_DEFENSE: 'DAY_FINAL_DEFENSE',
    DAY_FINAL_VOTING: 'DAY_FINAL_VOTING',
    NIGHT_ACTION: 'NIGHT_ACTION'
};

export const ROLES = {
    MAFIA: 'MAFIA',
    CITIZEN: 'CITIZEN',
    DOCTOR: 'DOCTOR',
    POLICE: 'POLICE'
};

export const MESSAGE_TYPES = {
    CHAT: 'CHAT',
    SYSTEM: 'SYSTEM',
    USER_JOINED: 'USER_JOINED',
    USER_LEFT: 'USER_LEFT',
    GAME_START: 'GAME_START',
    GAME_ENDED: 'GAME_ENDED',
    PHASE_SWITCHED: 'PHASE_SWITCHED',
    TIMER_UPDATE: 'TIMER_UPDATE',
    VOTE_RESULT_UPDATE: 'VOTE_RESULT_UPDATE',
    ROLE_ASSIGNED: 'ROLE_ASSIGNED'
};

export const REFRESH_COOLDOWN_MS = 3000;
