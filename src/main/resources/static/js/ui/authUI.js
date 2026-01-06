// Authentication UI Module

import * as api from '../api/apiService.js';
import * as ws from '../websocket/wsService.js';
import {
    resetAll,
    initFromStorage,
    setCurrentUser
} from '../state.js';
import { showElement, hideElement } from '../utils/helpers.js';

/**
 * Handle login form submission
 */
export async function handleLogin() {
    const userLoginId = document.getElementById('userLoginId').value;
    const userLoginPassword = document.getElementById('userLoginPassword').value;

    try {
        await api.login(userLoginId, userLoginPassword);

        // Hide login, show game screen
        hideElement('loginForm');
        hideElement('registerForm');
        showElement('gameScreen');

        // Connect WebSocket
        await ws.connect();

        return true;
    } catch (error) {
        alert(error.message);
        return false;
    }
}

/**
 * Handle registration form submission
 */
export async function handleRegister() {
    const userLoginId = document.getElementById('regUserLoginId').value;
    const userLoginPassword = document.getElementById('regUserLoginPassword').value;
    const userLoginPasswordConfirm = document.getElementById('regUserLoginPasswordConfirm').value;
    const nickname = document.getElementById('regNickname').value;

    // Validation
    if (userLoginPassword !== userLoginPasswordConfirm) {
        alert('비밀번호가 일치하지 않습니다.');
        return false;
    }

    if (!userLoginId || !userLoginPassword || !nickname) {
        alert('모든 필드를 입력해주세요.');
        return false;
    }

    try {
        await api.register(userLoginId, userLoginPassword, nickname);
        alert('회원가입이 완료되었습니다.');
        showLoginForm();
        return true;
    } catch (error) {
        alert(error.message || '회원가입에 실패했습니다.');
        return false;
    }
}

/**
 * Handle logout
 */
export function handleLogout() {
    ws.disconnect();
    resetAll();

    hideElement('gameScreen');
    showElement('loginForm');

    // Clear input fields
    const userLoginId = document.getElementById('userLoginId');
    const userLoginPassword = document.getElementById('userLoginPassword');
    if (userLoginId) userLoginId.value = '';
    if (userLoginPassword) userLoginPassword.value = '';

    // Clear role info
    const headerUserRole = document.getElementById('headerUserRole');
    if (headerUserRole) {
        headerUserRole.textContent = '';
        headerUserRole.style.display = 'none';
    }
}

/**
 * Show login form
 */
export function showLoginForm() {
    showElement('loginForm');
    hideElement('registerForm');
}

/**
 * Show register form with reset
 */
export function showRegisterForm() {
    hideElement('loginForm');
    showElement('registerForm');

    // Reset fields
    document.getElementById('regUserLoginId').value = '';
    document.getElementById('regUserLoginPassword').value = '';
    document.getElementById('regUserLoginPasswordConfirm').value = '';
    document.getElementById('regNickname').value = '';
    document.getElementById('passwordMatchStatus').textContent = '';
    document.getElementById('passwordMatchStatus').className = 'password-match-status empty';
    document.getElementById('registerBtn').disabled = false;
}

/**
 * Check password match on input
 */
export function checkPasswordMatch() {
    const password = document.getElementById('regUserLoginPassword').value;
    const confirmPassword = document.getElementById('regUserLoginPasswordConfirm').value;
    const statusElement = document.getElementById('passwordMatchStatus');
    const registerBtn = document.getElementById('registerBtn');

    if (confirmPassword === '') {
        statusElement.textContent = '';
        statusElement.className = 'password-match-status empty';
        registerBtn.disabled = false;
        return;
    }

    if (password === confirmPassword) {
        statusElement.textContent = '비밀번호가 일치합니다.';
        statusElement.className = 'password-match-status match';
        registerBtn.disabled = false;
    } else {
        statusElement.textContent = '비밀번호가 일치하지 않습니다.';
        statusElement.className = 'password-match-status mismatch';
        registerBtn.disabled = true;
    }
}

/**
 * Try to restore session from storage
 */
export async function tryRestoreSession() {
    if (!initFromStorage()) {
        return false;
    }

    try {
        const valid = await api.validateSession();
        if (valid) {
            hideElement('loginForm');
            hideElement('registerForm');
            showElement('gameScreen');
            await ws.connect();
            return true;
        }
    } catch (error) {
        console.log('Session validation failed:', error);
    }

    handleLogout();
    return false;
}
