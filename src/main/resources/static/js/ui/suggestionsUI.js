// Suggestions UI Module
// 채팅 추천 문구 표시 및 선택 처리

import * as api from '../api/apiService.js';
import { getCurrentUser, getState } from '../state.js';

let currentSuggestions = [];

/**
 * 현재 사용자의 역할과 게임 페이즈에 맞는 추천 문구 로드
 */
export async function loadSuggestions(role, phase, gameId) {
    if (!role || !phase) {
        hideSuggestions();
        return;
    }

    try {
        const suggestions = await api.fetchSuggestions(role, phase, gameId);
        currentSuggestions = suggestions || [];

        if (currentSuggestions.length > 0) {
            renderSuggestions(currentSuggestions);
            showSuggestions();
        } else {
            hideSuggestions();
        }
    } catch (error) {
        console.error('추천 문구 로드 실패:', error);
        hideSuggestions();
    }
}

/**
 * 추천 문구 렌더링
 */
function renderSuggestions(suggestions) {
    const container = document.getElementById('suggestionsContainer');
    if (!container) return;

    container.innerHTML = '';

    suggestions.forEach(text => {
        const button = document.createElement('button');
        button.className = 'suggestion-btn';
        button.textContent = text;
        button.onclick = () => handleSuggestionClick(text);
        container.appendChild(button);
    });
}

/**
 * 추천 문구 영역 표시
 */
function showSuggestions() {
    const area = document.getElementById('suggestionsArea');
    if (area) {
        area.style.display = 'block';
    }
}

/**
 * 추천 문구 영역 숨김
 */
function hideSuggestions() {
    const area = document.getElementById('suggestionsArea');
    if (area) {
        area.style.display = 'none';
    }
}

/**
 * 추천 문구 클릭 처리
 */
function handleSuggestionClick(text) {
    const messageInput = document.getElementById('messageInput');
    if (messageInput) {
        messageInput.value = text;
        messageInput.focus();
    }
}

/**
 * 게임 페이즈 변경 시 추천 문구 업데이트
 */
export async function updateSuggestionsForPhase(game) {
    const user = getCurrentUser();
    if (!game || !user) {
        hideSuggestions();
        return;
    }

    // 현재 플레이어 찾기
    const currentPlayer = game.players?.find(p => p.playerId === user.userLoginId);
    if (!currentPlayer || !currentPlayer.alive) {
        hideSuggestions();
        return;
    }

    const role = currentPlayer.role;
    const phase = game.gamePhase;
    const gameId = game.gameId;

    await loadSuggestions(role, phase, gameId);
}

/**
 * 추천 문구 초기화 (게임 종료 시)
 */
export function clearSuggestions() {
    currentSuggestions = [];
    hideSuggestions();
}
