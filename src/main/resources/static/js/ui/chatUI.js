// Chat UI Module

import { getCurrentUser } from '../state.js';
import { formatTime } from '../utils/helpers.js';

/**
 * Add a message to the chat area
 */
export function addMessage(chatMessage, messageType = 'other') {
    const chatMessages = document.getElementById('chatMessages');
    if (!chatMessages) return;

    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${messageType}`;

    const content = chatMessage.content || '';
    const senderName = chatMessage.senderName || chatMessage.senderId || 'Unknown';
    const timestamp = chatMessage.timestamp ? formatTime(chatMessage.timestamp) : '';

    if (messageType === 'system') {
        messageDiv.innerHTML = `
            <div class="system-message">${content}</div>
        `;
    } else if (messageType.includes('mafia')) {
        // Mafia chat styling
        messageDiv.innerHTML = `
            <div class="message-header">
                <span class="sender-name">🕵️ ${senderName}</span>
                <span class="message-time">${timestamp}</span>
            </div>
            <div class="message-content">${content}</div>
        `;
    } else {
        messageDiv.innerHTML = `
            <div class="message-header">
                <span class="sender-name">${senderName}</span>
                <span class="message-time">${timestamp}</span>
            </div>
            <div class="message-content">${content}</div>
        `;
    }

    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Add a system message
 */
export function addSystemMessage(content) {
    addMessage({
        senderId: 'SYSTEM',
        content: content,
        timestamp: Date.now()
    }, 'system');
}

/**
 * Clear all chat messages
 */
export function clearMessages() {
    const chatMessages = document.getElementById('chatMessages');
    if (chatMessages) {
        chatMessages.innerHTML = '';
    }
}

/**
 * Handle sending a message
 */
export function handleSendMessage(sendFunction) {
    const input = document.getElementById('messageInput');
    if (!input) return;

    const content = input.value.trim();
    if (!content) return;

    sendFunction(content);
    input.value = '';
}

/**
 * Handle key press in message input
 */
export function handleKeyPress(event, sendFunction) {
    if (event.key === 'Enter') {
        handleSendMessage(sendFunction);
    }
}

/**
 * Process incoming chat message
 * (Used by app.js or directly)
 */
export function processIncomingMessage(chatMessage) {
    const user = getCurrentUser();

    switch (chatMessage.type) {
        case 'CHAT':
            const messageType = chatMessage.senderId === user?.userLoginId ? 'self' : 'other';
            addMessage(chatMessage, messageType);
            break;

        case 'MAFIA_CHAT':
            const mafiaType = chatMessage.senderId === user?.userLoginId ? 'self mafia' : 'mafia';
            addMessage(chatMessage, mafiaType);
            break;

        case 'SYSTEM':
        case 'USER_JOINED':
        case 'USER_LEFT':
        case 'ROOM_CREATED':
            addMessage(chatMessage, 'system');
            break;

        default:
            // For other types, check if it's from system
            if (chatMessage.senderId === 'SYSTEM') {
                addMessage(chatMessage, 'system');
            }
    }
}

/**
 * Update chat input state based on game phase and role
 */
export function updateChatInputState(phase, role) {
    const input = document.getElementById('messageInput');
    const btn = document.querySelector('.chat-input button');

    if (!input || !btn) return;

    if (phase === 'NIGHT_ACTION') {
        if (role === 'MAFIA') {
            input.disabled = false;
            input.placeholder = '🕵️ 마피아에게 메시지 보내기...';
            btn.disabled = false;
        } else {
            input.disabled = true;
            input.placeholder = '🤫 밤에는 대화할 수 없습니다...';
            btn.disabled = true;
        }
    } else {
        input.disabled = false;
        input.placeholder = '메시지를 입력하세요...';
        btn.disabled = false;
    }
}
