// Shop API Service - 아이템 조회, 주문, 결제, 인벤토리 API

import { API_ENDPOINTS } from '../config.js';
import { getJwtToken } from '../state.js';

/**
 * JWT 인증 헤더가 포함된 API 요청
 */
async function apiRequest(url, options = {}) {
    const token = getJwtToken();
    const headers = {
        'Content-Type': 'application/json',
        ...options.headers
    };

    if (token) {
        headers['Authorization'] = 'Bearer ' + token;
    }

    return fetch(url, {
        ...options,
        headers,
        credentials: 'same-origin'
    });
}

/**
 * 전체 판매 아이템 목록 조회
 */
export async function fetchItems() {
    const response = await fetch(API_ENDPOINTS.ITEMS);
    if (!response.ok) throw new Error('아이템 목록 로드 실패');
    return response.json();
}

/**
 * 카테고리별 아이템 조회
 */
export async function fetchItemsByCategory(category) {
    const response = await fetch(`${API_ENDPOINTS.ITEMS}/category/${category}`);
    if (!response.ok) throw new Error('카테고리 아이템 로드 실패');
    return response.json();
}

/**
 * 사용자 인벤토리 조회
 */
export async function fetchInventory(userId) {
    const response = await apiRequest(`${API_ENDPOINTS.INVENTORY}/${userId}`);
    if (!response.ok) throw new Error('인벤토리 로드 실패');
    return response.json();
}

/**
 * 주문 생성
 */
export async function createOrder(items) {
    const response = await apiRequest(API_ENDPOINTS.ORDERS, {
        method: 'POST',
        body: JSON.stringify({ items })
    });
    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || '주문 생성 실패');
    }
    return response.json();
}

/**
 * 주문 상세 조회
 */
export async function fetchOrder(orderId) {
    const response = await apiRequest(`${API_ENDPOINTS.ORDERS}/${orderId}`);
    if (!response.ok) throw new Error('주문 조회 실패');
    return response.json();
}

/**
 * 결제 승인 요청
 */
export async function confirmPayment(paymentKey, orderId, amount) {
    const response = await apiRequest(API_ENDPOINTS.PAYMENTS_CONFIRM, {
        method: 'POST',
        body: JSON.stringify({ paymentKey, orderId, amount })
    });
    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || '결제 승인 실패');
    }
    return response.json();
}

/**
 * 결제 취소(환불) 요청
 */
export async function cancelPayment(orderId, cancelReason) {
    const response = await apiRequest(API_ENDPOINTS.PAYMENTS_CANCEL, {
        method: 'POST',
        body: JSON.stringify({ orderId, cancelReason })
    });
    if (!response.ok) throw new Error('결제 취소 실패');
    return response.json();
}
