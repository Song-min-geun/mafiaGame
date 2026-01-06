// Utility helper functions

/**
 * Format timestamp to readable time
 */
export function formatTime(timestamp) {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('ko-KR', {
        hour: '2-digit',
        minute: '2-digit'
    });
}

/**
 * Get phase end time in milliseconds
 * Handles both epoch millis (Long) and ISO string formats
 */
export function getPhaseEndMs(phaseEndTime) {
    if (!phaseEndTime) return null;

    // If it's already a number, return it
    if (typeof phaseEndTime === 'number') return phaseEndTime;

    // If it's a string, acts differently depending on format
    // 1. If it's a numeric string (epoch millis sent as string), parse it
    if (!isNaN(phaseEndTime) && !isNaN(parseFloat(phaseEndTime))) {
        return Number(phaseEndTime);
    }

    // 2. Otherwise assume ISO string
    return new Date(phaseEndTime).getTime();
}

/**
 * Calculate remaining seconds from phase end time
 */
export function getRemainingSeconds(phaseEndTime) {
    const endMs = getPhaseEndMs(phaseEndTime);
    if (!endMs) return 0;
    const diff = endMs - Date.now();
    return Math.max(0, Math.ceil(diff / 1000));
}

/**
 * Get role display name in Korean
 */
export function getRoleDisplayName(role) {
    const roleNames = {
        'MAFIA': '마피아',
        'CITIZEN': '시민',
        'DOCTOR': '의사',
        'POLICE': '경찰'
    };
    return roleNames[role] || role;
}

/**
 * Get phase display name in Korean
 */
export function getPhaseDisplayName(phase, currentPhase) {
    const day = currentPhase || 1;
    switch (phase) {
        case 'DAY_DISCUSSION':
            return `${day}일째 낮 대화`;
        case 'DAY_VOTING':
            return `${day}일째 투표`;
        case 'DAY_FINAL_DEFENSE':
            return `${day}일째 최후의 반론`;
        case 'DAY_FINAL_VOTE':
            return `${day}일째 찬성/반대`;
        case 'NIGHT_ACTION':
            return `${day}일째 밤 액션`;
        default:
            return phase;
    }
}

/**
 * Debounce function
 */
export function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

/**
 * Show element by ID
 */
export function showElement(elementId) {
    const el = document.getElementById(elementId);
    if (el) {
        el.style.display = '';
        el.classList.remove('hidden');
    }
}

/**
 * Hide element by ID
 */
export function hideElement(elementId) {
    const el = document.getElementById(elementId);
    if (el) {
        el.style.display = 'none';
        el.classList.add('hidden');
    }
}

/**
 * Add class to element
 */
export function addClass(elementId, className) {
    const el = document.getElementById(elementId);
    if (el) el.classList.add(className);
}

/**
 * Remove class from element
 */
export function removeClass(elementId, className) {
    const el = document.getElementById(elementId);
    if (el) el.classList.remove(className);
}
