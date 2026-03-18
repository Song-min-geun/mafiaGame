// WebSocket Service

import { WS_TOPICS, WS_DESTINATIONS } from '../config.js';
import {
    getJwtToken,
    getCurrentUser,
    setStompClient,
    getStompClient,
    setCurrentRoomSubscription,
    getState
} from '../state.js';

let privateSubscription = null;
let lobbySubscription = null;

/**
 * Connect to WebSocket server
 */
export function connect() {
    return new Promise((resolve, reject) => {
        const client = getStompClient();
        if (client && client.connected) {
            resolve();
            return;
        }

        const socket = new SockJS('/ws');
        const stompClient = Stomp.over(socket);

        const token = getJwtToken();
        if (!token) {
            reject(new Error('JWT 토큰이 없습니다.'));
            return;
        }

        stompClient.connect(
            { 'Authorization': 'Bearer ' + token },
            (frame) => {
                console.log('WebSocket 연결 성공:', frame);
                setStompClient(stompClient);
                updateConnectionStatus(true);
                resolve(stompClient);
            },
            (error) => {
                console.error('WebSocket 연결 실패:', error);
                updateConnectionStatus(false);
                reject(error);
            }
        );
    });
}

/**
 * Disconnect from WebSocket server
 */
export function disconnect() {
    const client = getStompClient();
    if (client && client.connected) {
        client.disconnect();
    }
    setStompClient(null);
    updateConnectionStatus(false);
}

/**
 * Subscribe to private messages
 */
export function subscribeToPrivateMessages(onMessage) {
    const client = getStompClient();
    const user = getCurrentUser();

    if (!client || !user) {
        console.error('Cannot subscribe: client or user not available');
        return;
    }

    const topic = WS_TOPICS.PRIVATE(user.userLoginId);
    console.log(`개인 메시지 구독: ${topic}`);

    privateSubscription = client.subscribe(topic, (message) => {
        const data = JSON.parse(message.body);
        console.log('개인 메시지 수신:', data);
        onMessage(data);
    });
}

/**
 * Subscribe to lobby updates
 */
export function subscribeToLobby(onUpdate) {
    const client = getStompClient();

    if (!client || !client.connected) {
        return;
    }

    console.log('로비 구독 시작');
    lobbySubscription = client.subscribe(WS_TOPICS.LOBBY, (message) => {
        const data = JSON.parse(message.body);
        if (data.type === 'ROOM_LIST_UPDATED') {
            onUpdate();
        }
    });
}

/**
 * Subscribe to a room
 */
export function subscribeToRoom(roomId, onMessage) {
    const client = getStompClient();

    if (!client || !client.connected) {
        console.error('Cannot subscribe to room: not connected');
        return null;
    }

    // 기존 구독이 있으면 먼저 해제 (중복 구독 방지)
    const state = getCurrentRoomSubscriptionFromState();
    if (state) {
        console.log('기존 방 구독 해제');
        state.unsubscribe();
        setCurrentRoomSubscription(null);
    }

    const topic = WS_TOPICS.ROOM(roomId);
    console.log(`방 구독: ${topic}`);

    const subscription = client.subscribe(topic, (message) => {
        const data = JSON.parse(message.body);
        console.log('방 메시지 수신:', data);
        onMessage(data);
    });

    setCurrentRoomSubscription(subscription);
    return subscription;
}

// Helper to get current subscription from state
function getCurrentRoomSubscriptionFromState() {
    return getState().currentRoomSubscription;
}

/**
 * Unsubscribe from room
 */
export function unsubscribeFromRoom(subscription) {
    if (subscription) {
        subscription.unsubscribe();
    }
    setCurrentRoomSubscription(null);
}

/**
 * Send message to destination
 */
export function send(destination, payload) {
    const client = getStompClient();

    if (!client || !client.connected) {
        throw new Error('WebSocket 연결이 없습니다.');
    }

    client.send(destination, {}, JSON.stringify(payload));
}

/**
 * Join room via WebSocket
 */
export function joinRoom(roomId) {
    send(WS_DESTINATIONS.ROOM_JOIN, { roomId });
}

/**
 * Leave room via WebSocket
 */
export function leaveRoom(roomId) {
    send(WS_DESTINATIONS.ROOM_LEAVE, { roomId });
}

/**
 * Send chat message
 */
export function sendChatMessage(roomId, content) {
    send(WS_DESTINATIONS.CHAT_SEND, { roomId, content });
}

/**
 * Send vote
 */
export function sendVote(gameId, targetId) {
    send(WS_DESTINATIONS.GAME_VOTE, { gameId, targetId });
}

/**
 * Send final vote
 */
export function sendFinalVote(gameId, vote) {
    // Backend expects voteChoice: "AGREE" or "DISAGREE"
    // If vote is boolean true -> AGREE, false -> DISAGREE
    // If vote is string -> use as is
    let voteChoice;
    if (typeof vote === 'string') {
        voteChoice = vote;
    } else {
        voteChoice = vote ? "AGREE" : "DISAGREE";
    }
    send(WS_DESTINATIONS.GAME_FINAL_VOTE, { gameId, voteChoice });
}

/**
 * Send night action
 */
export function sendNightAction(gameId, targetId) {
    send(WS_DESTINATIONS.GAME_NIGHT_ACTION, { gameId, targetId });
}

// Helper to update connection status UI
function updateConnectionStatus(connected) {
    const statusElem = document.getElementById('headerConnectionStatus');
    if (statusElem) {
        statusElem.textContent = connected ? '🟢' : '🔴';
    }
}
