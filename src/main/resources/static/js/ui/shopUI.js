// Shop UI Module - 상점 / 인벤토리 / 결제 화면 관리

import * as shopApi from '../api/shopService.js';
import { getCurrentUser } from '../state.js';

const CATEGORY_LABELS = {
    COSMETIC: { label: '코스메틱', icon: '🎨', desc: '채팅 말풍선, 프로필 프레임' },
    BOOST: { label: '부스트', icon: '⚡', desc: '경험치 2배 부스트' },
    SEASON_PASS: { label: '시즌패스', icon: '🏆', desc: '한정 콘텐츠 해금' },
    COIN: { label: '코인', icon: '🪙', desc: '인앱 가상 화폐' }
};

let currentItems = [];
let cart = [];
let currentTab = 'shop'; // 'shop' | 'inventory' | 'orders'

/**
 * 상점 패널 표시/숨김 토글
 */
export function toggleShop() {
    const panel = document.getElementById('shopPanel');
    if (!panel) return;

    if (panel.classList.contains('hidden')) {
        panel.classList.remove('hidden');
        loadShop();
    } else {
        panel.classList.add('hidden');
    }
}

/**
 * 상점 아이템 목록 로드
 */
export async function loadShop(category = null) {
    currentTab = 'shop';
    updateTabUI();

    const container = document.getElementById('shopContent');
    if (!container) return;

    container.innerHTML = '<div class="shop-loading"><div class="spinner"></div>로딩 중...</div>';

    try {
        let items;
        if (category) {
            items = await shopApi.fetchItemsByCategory(category);
        } else {
            items = await shopApi.fetchItems();
        }
        currentItems = items;
        renderItems(items, container);
    } catch (error) {
        container.innerHTML = `<div class="shop-empty">❌ 아이템을 불러올 수 없습니다.</div>`;
        console.error('상점 로드 실패:', error);
    }
}

/**
 * 아이템 목록 렌더링
 */
function renderItems(items, container) {
    if (!items || items.length === 0) {
        container.innerHTML = '<div class="shop-empty">🛒 등록된 아이템이 없습니다.</div>';
        return;
    }

    // 카테고리별 그룹핑
    const grouped = {};
    items.forEach(item => {
        if (!grouped[item.category]) grouped[item.category] = [];
        grouped[item.category].push(item);
    });

    let html = '';
    for (const [category, categoryItems] of Object.entries(grouped)) {
        const info = CATEGORY_LABELS[category] || { label: category, icon: '📦', desc: '' };
        html += `
            <div class="shop-category-section">
                <div class="shop-category-header">
                    <span class="category-icon">${info.icon}</span>
                    <h3>${info.label}</h3>
                    <span class="category-desc">${info.desc}</span>
                </div>
                <div class="shop-items-grid">
                    ${categoryItems.map(item => renderItemCard(item)).join('')}
                </div>
            </div>
        `;
    }

    container.innerHTML = html;
}

/**
 * 개별 아이템 카드 렌더링
 */
function renderItemCard(item) {
    const inCart = cart.find(c => c.itemId === item.itemId);
    const durationText = item.durationDays ? `${item.durationDays}일` : '영구';
    const info = CATEGORY_LABELS[item.category] || { icon: '📦' };

    return `
        <div class="shop-item-card ${inCart ? 'in-cart' : ''}" data-item-id="${item.itemId}">
            <div class="item-card-icon">${info.icon}</div>
            <div class="item-card-body">
                <h4 class="item-name">${item.itemName}</h4>
                <p class="item-desc">${item.description || ''}</p>
                <div class="item-meta">
                    <span class="item-duration">${durationText}</span>
                </div>
            </div>
            <div class="item-card-footer">
                <span class="item-price">₩${item.price.toLocaleString()}</span>
                <button class="item-add-btn ${inCart ? 'added' : ''}" 
                        onclick="window.shopAddToCart(${item.itemId})">
                    ${inCart ? '✓ 담김' : '담기'}
                </button>
            </div>
        </div>
    `;
}

/**
 * 장바구니에 아이템 추가
 */
export function addToCart(itemId) {
    const item = currentItems.find(i => i.itemId === itemId);
    if (!item) return;

    const existing = cart.find(c => c.itemId === itemId);
    if (existing) {
        existing.quantity += 1;
    } else {
        cart.push({ itemId: item.itemId, itemName: item.itemName, price: item.price, quantity: 1 });
    }

    updateCartUI();
    // Re-render items to show 'in-cart' state
    const container = document.getElementById('shopContent');
    if (container && currentTab === 'shop') {
        renderItems(currentItems, container);
    }
}

/**
 * 장바구니에서 아이템 제거
 */
export function removeFromCart(itemId) {
    cart = cart.filter(c => c.itemId !== itemId);
    updateCartUI();
    const container = document.getElementById('shopContent');
    if (container && currentTab === 'shop') {
        renderItems(currentItems, container);
    }
}

/**
 * 장바구니 UI 업데이트
 */
function updateCartUI() {
    const badge = document.getElementById('cartBadge');
    const cartContainer = document.getElementById('cartItems');
    const cartTotal = document.getElementById('cartTotal');
    const checkoutBtn = document.getElementById('checkoutBtn');

    if (badge) {
        const totalCount = cart.reduce((sum, c) => sum + c.quantity, 0);
        badge.textContent = totalCount;
        badge.style.display = totalCount > 0 ? 'flex' : 'none';
    }

    if (cartContainer) {
        if (cart.length === 0) {
            cartContainer.innerHTML = '<div class="cart-empty">장바구니가 비어있습니다</div>';
        } else {
            cartContainer.innerHTML = cart.map(item => `
                <div class="cart-item">
                    <div class="cart-item-info">
                        <span class="cart-item-name">${item.itemName}</span>
                        <span class="cart-item-qty">x${item.quantity}</span>
                    </div>
                    <div class="cart-item-actions">
                        <span class="cart-item-price">₩${(item.price * item.quantity).toLocaleString()}</span>
                        <button class="cart-remove-btn" onclick="window.shopRemoveFromCart(${item.itemId})">✕</button>
                    </div>
                </div>
            `).join('');
        }
    }

    const total = cart.reduce((sum, c) => sum + c.price * c.quantity, 0);
    if (cartTotal) cartTotal.textContent = `₩${total.toLocaleString()}`;
    if (checkoutBtn) checkoutBtn.disabled = cart.length === 0;
}

/**
 * 결제 진행 (주문 생성 → 결제 승인 시뮬레이션)
 */
export async function checkout() {
    if (cart.length === 0) return;

    const checkoutBtn = document.getElementById('checkoutBtn');
    if (checkoutBtn) {
        checkoutBtn.disabled = true;
        checkoutBtn.textContent = '결제 처리 중...';
    }

    try {
        const items = cart.map(c => ({ itemId: c.itemId, quantity: c.quantity }));
        const order = await shopApi.createOrder(items);

        // 결제 승인 시뮬레이션 (실제로는 Toss Payments SDK 위젯 사용)
        const totalAmount = cart.reduce((sum, c) => sum + c.price * c.quantity, 0);
        const payment = await shopApi.confirmPayment(
            'test_pk_' + Date.now(),  // 테스트용 paymentKey
            order.orderId,
            totalAmount
        );

        // 성공
        cart = [];
        updateCartUI();
        showPaymentSuccess(order.orderId, totalAmount);
    } catch (error) {
        console.error('결제 실패:', error);
        showPaymentError(error.message);
    } finally {
        if (checkoutBtn) {
            checkoutBtn.disabled = false;
            checkoutBtn.textContent = '결제하기';
        }
    }
}

/**
 * 결제 성공 모달 표시
 */
function showPaymentSuccess(orderId, amount) {
    const container = document.getElementById('shopContent');
    if (!container) return;

    container.innerHTML = `
        <div class="payment-result success">
            <div class="result-icon">✅</div>
            <h3>결제 완료!</h3>
            <p>주문번호: <span class="order-id">${orderId}</span></p>
            <p>결제 금액: <span class="payment-amount">₩${amount.toLocaleString()}</span></p>
            <p class="result-note">아이템이 인벤토리에 자동 지급됩니다.</p>
            <button class="result-btn" onclick="window.shopLoadShop()">상점으로 돌아가기</button>
        </div>
    `;
}

/**
 * 결제 실패 표시
 */
function showPaymentError(message) {
    alert(`결제 실패: ${message}`);
}

/**
 * 인벤토리 탭 로드
 */
export async function loadInventory() {
    currentTab = 'inventory';
    updateTabUI();

    const container = document.getElementById('shopContent');
    if (!container) return;

    container.innerHTML = '<div class="shop-loading"><div class="spinner"></div>로딩 중...</div>';

    const user = getCurrentUser();
    if (!user) {
        container.innerHTML = '<div class="shop-empty">로그인이 필요합니다.</div>';
        return;
    }

    try {
        const items = await shopApi.fetchInventory(user.userId);
        renderInventory(items, container);
    } catch (error) {
        container.innerHTML = `<div class="shop-empty">인벤토리를 불러올 수 없습니다.</div>`;
        console.error('인벤토리 로드 실패:', error);
    }
}

/**
 * 인벤토리 렌더링
 */
function renderInventory(items, container) {
    if (!items || items.length === 0) {
        container.innerHTML = `
            <div class="shop-empty">
                <div class="empty-icon">📦</div>
                <p>보유 중인 아이템이 없습니다.</p>
                <button class="result-btn" onclick="window.shopLoadShop()">상점 둘러보기</button>
            </div>
        `;
        return;
    }

    container.innerHTML = `
        <div class="inventory-grid">
            ${items.map(inv => {
        const info = CATEGORY_LABELS[inv.item?.category] || { icon: '📦', label: '기타' };
        const expiresText = inv.expiresAt
            ? `만료: ${new Date(inv.expiresAt).toLocaleDateString()}`
            : '영구';
        return `
                    <div class="inventory-item">
                        <div class="inv-icon">${info.icon}</div>
                        <div class="inv-info">
                            <span class="inv-name">${inv.item?.itemName || '알 수 없음'}</span>
                            <span class="inv-qty">x${inv.quantity}</span>
                            <span class="inv-expires">${expiresText}</span>
                        </div>
                    </div>
                `;
    }).join('')}
        </div>
    `;
}

/**
 * 탭 UI 업데이트
 */
function updateTabUI() {
    document.querySelectorAll('.shop-tab-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === currentTab);
    });
}

/**
 * 상점 패널 초기화 (장바구니 초기 상태)
 */
export function initShop() {
    updateCartUI();
}
