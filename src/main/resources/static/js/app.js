// 전역 변수
let stompClient = null;
let currentRoom = null;
let currentUser = null;
let currentRoomInfo = null; // ❗ 추가: 현재 방 정보 저장
let jwtToken = null;
let currentRoomSubscription = null;

// --- 로그인/회원가입/로그아웃 관련 함수들 ---
async function login(event) {
    if (event) event.preventDefault();
    const userLoginId = document.getElementById('userLoginId').value;
    const userLoginPassword = document.getElementById('userLoginPassword').value;
    try {
        const loginResponse = await fetch('/api/users/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userLoginId, userLoginPassword })
        });
        const loginResult = await loginResponse.json();
        if (!loginResult.success) throw new Error(loginResult.message || '로그인 실패');
        const token = loginResult.data.token;
        jwtToken = 'Bearer ' + token;
        const userResponse = await fetch('/api/users/me', { headers: { 'Authorization': jwtToken } });
        const userResult = await userResponse.json();
        if (!userResult.success) throw new Error(userResult.message || '사용자 정보 조회 실패');
        currentUser = userResult.data;
        localStorage.setItem('jwtToken', jwtToken);
        localStorage.setItem('currentUser', JSON.stringify(currentUser));
        document.getElementById('loginForm').classList.add('hidden');
        document.getElementById('registerForm').classList.add('hidden');
        document.getElementById('gameScreen').classList.remove('hidden');
        connectWebSocket();
        loadRooms();
        updateUserInfo();
    } catch (error) {
        alert(error.message);
    }
}

async function register(event) {
    if (event) event.preventDefault();
    const userLoginId = document.getElementById('regUserLoginId').value;
    const userLoginPassword = document.getElementById('regUserLoginPassword').value;
    const nickname = document.getElementById('regNickname').value;
    try {
        const response = await fetch('/api/users/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userLoginId, userLoginPassword, nickname })
        });
        const result = await response.json();
        if (result.success) {
            alert('회원가입이 완료되었습니다.');
            showLogin();
        } else {
            alert(result.message || '회원가입에 실패했습니다.');
        }
    } catch (error) {
        alert('회원가입 중 오류가 발생했습니다.');
    }
}

function logout() {
    if (!currentUser) return;
    if (stompClient && stompClient.connected) {
        stompClient.disconnect(() => console.log("WebSocket disconnected."));
    }
    currentUser = null;
    currentRoom = null;
    currentRoomInfo = null; // ❗ 추가: 방 정보 초기화
    jwtToken = null;
    currentRoomSubscription = null;
    stompClient = null;
    localStorage.clear();
    document.getElementById('gameScreen').classList.add('hidden');
    document.getElementById('loginForm').classList.remove('hidden');
    document.getElementById('userLoginId').value = '';
    document.getElementById('userLoginPassword').value = '';
    clearChatMessages();
    updateUserInfo();
}

function showLogin() {
    document.getElementById('loginForm').classList.remove('hidden');
    document.getElementById('registerForm').classList.add('hidden');
}

function showRegister() {
    document.getElementById('loginForm').classList.add('hidden');
    document.getElementById('registerForm').classList.remove('hidden');
}

// --- WebSocket 연결 관련 함수 ---
function connectWebSocket() {
    if (stompClient && stompClient.connected) return;
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    const token = jwtToken ? jwtToken.replace('Bearer ', '') : null;
    if (!token) return;
    stompClient.connect({ 'Authorization': 'Bearer ' + token },
        frame => {
            console.log('Connected: ' + frame);
            document.getElementById('connectionStatus').textContent = '연결됨';
        },
        error => {
            console.error('Connection error: ', error);
            document.getElementById('connectionStatus').textContent = '연결 실패';
        }
    );
}

// --- 방 관리 및 메시지 관련 함수 ---

async function loadRooms() {
    try {
        // ❗ 추가: JWT 토큰 유효성 검사
        if (!jwtToken) {
            console.error('JWT 토큰이 없습니다.');
            return;
        }

        const response = await fetch('/api/chat/rooms', { 
            headers: { 'Authorization': jwtToken } 
        });
        
        if (response.status === 401) {
            // 인증 실패 시 로그아웃
            console.error('인증 실패 - 로그아웃 처리');
            logout();
            return;
        }
        
        if (!response.ok) {
            throw new Error(`방 목록 로드 실패: ${response.status} ${response.statusText}`);
        }
        
        const rooms = await response.json();
        const roomList = document.getElementById('roomList');
        
        if (!roomList) {
            console.error('roomList 요소를 찾을 수 없습니다.');
            return;
        }
        
        roomList.innerHTML = '';
        
        if (rooms.length === 0) {
            roomList.innerHTML = '<div class="room-item no-rooms">현재 생성된 방이 없습니다.</div>';
        } else {
            rooms.forEach(room => {
                const roomItem = document.createElement('div');
                roomItem.className = 'room-item';
                
                // ❗ 추가: 방 정보 표시 개선
                const participantCount = room.participants ? room.participants.length : 0;
                const maxPlayers = room.maxPlayers || 8;
                const isCurrentRoom = currentRoom === room.roomId;
                const roomName = room.roomName || `방 ${room.roomId}`;
                
                roomItem.innerHTML = `
                    <div class="room-info">
                        <strong class="room-name" title="${roomName}">${roomName}</strong>
                        <span class="room-count">${participantCount}/${maxPlayers}</span>
                    </div>
                    ${isCurrentRoom ? '<span class="current-room-badge">현재 방</span>' : ''}
                `;
                
                roomItem.onclick = () => joinRoom(room.roomId);
                roomList.appendChild(roomItem);
            });
        }
        
        console.log('방 목록 로드 완료:', rooms.length + '개 방');
        
    } catch (error) {
        console.error('방 목록 로드 중 오류:', error);
        const roomList = document.getElementById('roomList');
        if (roomList) {
            roomList.innerHTML = '<div class="room-item error">방 목록을 불러올 수 없습니다.</div>';
        }
    }
}

async function createRoom() {
    const roomName = prompt('방 이름을 입력하세요:');
    if (!roomName) return;
    try {
        const response = await fetch('/api/chat/rooms', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': jwtToken },
            body: JSON.stringify({ roomName, hostId: currentUser.userLoginId, maxPlayers: 8 })
        });
        if (!response.ok) throw new Error('방 생성 실패');
        const room = await response.json();
        await joinRoom(room.roomId);
    } catch (error) {
        alert(error.message);
    }
}

async function joinRoom(roomId) {
    if (currentRoom === roomId) return;
    if (currentRoom) await leaveRoom();
    
    try {
        // API를 통한 방 입장
        const response = await fetch(`/api/chat/rooms/${roomId}/join`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': jwtToken },
            body: JSON.stringify({ userId: currentUser.userLoginId, userName: currentUser.nickname })
        });
        
        if (!response.ok) throw new Error('방 입장에 실패했습니다.');
        
        // ❗ 추가: 방 정보 저장
        const roomData = await response.json();
        currentRoom = roomId;
        currentRoomInfo = roomData;
        
        subscribeToRoom(roomId);
        clearChatMessages();
        updateUserInfo();
        loadRooms();
        
        // ❗ 추가: WebSocket을 통해 방 입장 시스템 메시지 전송
        if (stompClient && stompClient.connected) {
            const joinPayload = {
                roomId: roomId
            };
            stompClient.send("/app/room.join", {}, JSON.stringify(joinPayload));
            console.log('방 입장 시스템 메시지 전송:', joinPayload);
        }
    } catch (error) {
        alert(error.message);
    }
}

async function leaveRoom() {
    if (!currentRoom) return;
    
    try {
        // ❗ 추가: WebSocket을 통해 방 나가기 시스템 메시지 전송 (API 호출 전)
        if (stompClient && stompClient.connected) {
            const leavePayload = {
                roomId: currentRoom
            };
            stompClient.send("/app/room.leave", {}, JSON.stringify(leavePayload));
            console.log('방 나가기 시스템 메시지 전송:', leavePayload);
        }
        
        // API를 통한 방 나가기
        const response = await fetch(`/api/chat/rooms/${currentRoom}/leave`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': jwtToken },
            body: JSON.stringify({ userId: currentUser.userLoginId })
        });
        
        if (!response.ok) throw new Error('방 나가기에 실패했습니다.');
        
        unsubscribeFromRoom();
        currentRoom = null;
        currentRoomInfo = null; // ❗ 추가: 방 정보 초기화
        clearChatMessages();
        updateUserInfo();
        loadRooms();
    } catch (error) {
        alert(error.message);
    }
}

function subscribeToRoom(roomId) {
    if (!stompClient || !stompClient.connected) return;
    const destination = `/topic/room.${roomId}`;
    currentRoomSubscription = stompClient.subscribe(destination, (message) => {
        const chatMessage = JSON.parse(message.body);
        const messageType = chatMessage.senderId === currentUser.userLoginId ? 'self' : 'other';
        addMessage(chatMessage, messageType);
    });
    console.log(`Subscribed to ${destination}`);
}

function unsubscribeFromRoom() {
    if (currentRoomSubscription) {
        currentRoomSubscription.unsubscribe();
        currentRoomSubscription = null;
    }
}

function sendMessage() {
    const messageInput = document.getElementById('messageInput');
    const messageContent = messageInput.value.trim();
    if (messageContent && currentRoom && stompClient && stompClient.connected) {
        const chatMessage = {
            roomId: currentRoom,
            content: messageContent,
        };
        stompClient.send("/app/chat.sendMessage", {}, JSON.stringify(chatMessage));
        messageInput.value = '';
    }
}

function addMessage(chatMessage, messageType) {
    const chatMessages = document.getElementById('chatMessages');
    const messageElement = document.createElement('div');
    
    // ❗ 추가: 시스템 메시지 구분
    if (chatMessage.senderId === 'SYSTEM') {
        messageElement.classList.add('message', 'system');
        messageElement.innerHTML = `
            <div class="system-message">
                <span class="system-icon">🔔</span>
                <span class="system-content">${chatMessage.content}</span>
            </div>
        `;
    } else {
        messageElement.classList.add('message', messageType);
        const sender = document.createElement('div');
        sender.className = 'sender';
        sender.textContent = messageType === 'self' ? '나' : chatMessage.senderName;
        const content = document.createElement('div');
        content.className = 'content';
        content.textContent = chatMessage.content;
        messageElement.appendChild(sender);
        messageElement.appendChild(content);
    }
    
    chatMessages.appendChild(messageElement);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

// --- 나머지 유틸리티 함수들 ---
function clearChatMessages() {
    document.getElementById('chatMessages').innerHTML = '';
}

function updateUserInfo() {
    // ❗ 수정: 헤더의 사용자 정보 업데이트
    const headerUserInfo = document.getElementById('headerUserInfo');
    const headerUserName = document.getElementById('headerUserName');
    const headerConnectionStatus = document.getElementById('headerConnectionStatus');
    const headerCurrentRoom = document.getElementById('headerCurrentRoom');
    
    if (currentUser) {
        // 헤더 사용자 정보 표시
        if (headerUserInfo) headerUserInfo.style.display = 'flex';
        if (headerUserName) headerUserName.textContent = currentUser.nickname;
        
        // 사이드바 사용자 정보 (기존 코드 유지)
        const currentUserName = document.getElementById('currentUserName');
        if (currentUserName) currentUserName.textContent = currentUser.nickname;
    } else {
        // 헤더 사용자 정보 숨김
        if (headerUserInfo) headerUserInfo.style.display = 'none';
        
        // 사이드바 사용자 정보 (기존 코드 유지)
        const currentUserName = document.getElementById('currentUserName');
        if (currentUserName) currentUserName.textContent = '';
    }
    
    // 연결 상태 업데이트
    const connectionStatus = document.getElementById('connectionStatus');
    if (connectionStatus) connectionStatus.textContent = currentRoom || '없음';
    
    // 헤더 연결 상태 업데이트
    if (headerConnectionStatus) {
        if (stompClient && stompClient.connected) {
            headerConnectionStatus.textContent = '🟢';
            headerConnectionStatus.className = 'connection-indicator connected';
        } else {
            headerConnectionStatus.textContent = '🔴';
            headerConnectionStatus.className = 'connection-indicator disconnected';
        }
    }
    
    // 현재 방 정보 업데이트
    const currentRoomStatus = document.getElementById('currentRoomStatus');
    if (currentRoomStatus) currentRoomStatus.textContent = currentRoom || '없음';
    
    // 헤더 현재 방 정보 업데이트
    if (headerCurrentRoom) {
        if (currentRoom && currentRoomInfo) {
            // ❗ 수정: 방 제목 표시
            const roomDisplayName = currentRoomInfo.roomName || currentRoom;
            headerCurrentRoom.textContent = roomDisplayName;
            headerCurrentRoom.style.display = 'inline-block';
        } else if (currentRoom) {
            // 방 정보가 없으면 방 ID 표시
            headerCurrentRoom.textContent = currentRoom;
            headerCurrentRoom.style.display = 'inline-block';
        } else {
            headerCurrentRoom.style.display = 'none';
        }
    }
    
    // ❗ 추가: 나가기 버튼 표시/숨김 제어
    const leaveRoomBtn = document.getElementById('leaveRoomBtn');
    if (leaveRoomBtn) {
        if (currentRoom) {
            leaveRoomBtn.style.display = 'inline-block';
        } else {
            leaveRoomBtn.style.display = 'none';
        }
    }
}

function handleKeyPress(event) {
    if (event.key === 'Enter') {
        sendMessage();
    }
}

window.onload = function() {
    const savedToken = localStorage.getItem('jwtToken');
    const savedUser = localStorage.getItem('currentUser');
    if (savedToken && savedUser) {
        try {
            jwtToken = savedToken;
            currentUser = JSON.parse(savedUser);
            document.getElementById('loginForm').classList.add('hidden');
            document.getElementById('registerForm').classList.add('hidden');
            document.getElementById('gameScreen').classList.remove('hidden');
            connectWebSocket();
            loadRooms();
            updateUserInfo();
        } catch (e) {
            console.error("Failed to parse user data from localStorage", e);
            localStorage.clear();
        }
    }
};