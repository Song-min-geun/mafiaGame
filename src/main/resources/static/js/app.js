// 전역 변수
let stompClient = null;
let currentRoom = null;
let currentUser = null;
let currentRoomInfo = null;
let jwtToken = null;
let currentRoomSubscription = null;
let lastRefreshTime = 0; // ❗ 추가: 마지막 새로고침 시간
let isGameStarted = false; // ❗ 추가: 게임 시작 상태

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

// ❗ 추가: 비밀번호 일치 확인 함수
function checkPasswordMatch() {
    const password = document.getElementById('regUserLoginPassword').value;
    const confirmPassword = document.getElementById('regUserLoginPasswordConfirm').value;
    const statusElement = document.getElementById('passwordMatchStatus');
    const registerBtn = document.getElementById('registerBtn');
    
    // 비밀번호 확인란이 비어있으면 상태 메시지 숨김
    if (confirmPassword === '') {
        statusElement.textContent = '';
        statusElement.className = 'password-match-status empty';
        registerBtn.disabled = false;
        return;
    }
    
    // 비밀번호 확인란에 타이핑이 시작되면 검사 시작
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

async function register(event) {
    if (event) event.preventDefault();
    
    const userLoginId = document.getElementById('regUserLoginId').value;
    const userLoginPassword = document.getElementById('regUserLoginPassword').value;
    const userLoginPasswordConfirm = document.getElementById('regUserLoginPasswordConfirm').value;
    const nickname = document.getElementById('regNickname').value;
    
    try {
        // 비밀번호 일치 확인
        if (userLoginPassword !== userLoginPasswordConfirm) {
            alert('비밀번호가 일치하지 않습니다.');
            return;
        }
        
        // 빈 값 확인
        if (!userLoginId || !userLoginPassword || !nickname) {
            alert('모든 필드를 입력해주세요.');
            return;
        }
        
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
    
    // ❗ 추가: 회원가입 폼 초기화
    document.getElementById('regUserLoginId').value = '';
    document.getElementById('regUserLoginPassword').value = '';
    document.getElementById('regUserLoginPasswordConfirm').value = '';
    document.getElementById('regNickname').value = '';
    document.getElementById('passwordMatchStatus').textContent = '';
    document.getElementById('passwordMatchStatus').className = 'password-match-status empty';
    document.getElementById('registerBtn').disabled = false;
}

// --- WebSocket 연결 관련 함수 ---
function connectWebSocket() {
    console.log('connectWebSocket 함수 시작');
    console.log('현재 stompClient:', stompClient);
    console.log('현재 연결 상태:', stompClient?.connected);
    
    if (stompClient && stompClient.connected) {
        console.log('이미 WebSocket이 연결되어 있습니다.');
        return;
    }
    
    console.log('새로운 WebSocket 연결을 시도합니다...');
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    
    const token = jwtToken ? jwtToken.replace('Bearer ', '') : null;
    if (!token) {
        console.error('JWT 토큰이 없습니다. WebSocket 연결을 건너뜁니다.');
        return;
    }
    
    console.log('JWT 토큰으로 WebSocket 연결 시도...');
    stompClient.connect({ 'Authorization': 'Bearer ' + token },
        frame => {
            console.log('✅ WebSocket 연결 성공:', frame);
            document.getElementById('connectionStatus').textContent = '연결됨';
        },
        error => {
            console.error('❌ WebSocket 연결 실패:', error);
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
            body: JSON.stringify({ roomName, userId: currentUser.userLoginId})
        });
        if (!response.ok) throw new Error('방 생성 실패');
        const room = await response.json();
        
        // ❗ 수정: 방 생성 후 방 정보 설정 (자동 입장 제거)
        currentRoom = room.roomId;
        currentRoomInfo = room;
        
        // ❗ 추가: WebSocket 연결 확인 및 재연결
        if (!stompClient || !stompClient.connected) {
            console.log('WebSocket 연결이 없습니다. 연결을 시도합니다...');
            connectWebSocket();
            // 연결 완료까지 잠시 대기
            await new Promise(resolve => setTimeout(resolve, 1000));
        }
        
        // ❗ 추가: 방 구독
        subscribeToRoom(room.roomId);
        
        // ❗ 추가: 버튼 상태 업데이트
        updateGameButtons();
        
        console.log('방 생성 완료:', room.roomId);
        
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
        const responseData = await response.json();
        currentRoom = roomId;
        currentRoomInfo = responseData.room; // room 객체를 저장
        
        subscribeToRoom(roomId);
        clearChatMessages();
        
        // ❗ 수정: 시스템 메시지를 올바른 형식으로 추가
        const systemMessage = {
            type: 'JOIN',
            roomId: roomId,
            senderId: 'SYSTEM',
            senderName: '시스템',
            content: (currentUser.nickname || currentUser.userLoginId || '사용자') + '님이 입장하였습니다.',
            timestamp: Date.now()
        };
        addMessage(systemMessage, 'system');
        
        updateUserInfo();
        loadRooms();
        
        // ❗ 추가: 버튼 표시/숨김 로직
        updateGameButtons();
        
        // ❗ 추가: 방장 여부 확인 및 로그
        if (currentRoomInfo && currentRoomInfo.participants) {
            const isHost = currentRoomInfo.participants.some(p => 
                p.userLoginId === currentUser.userLoginId && p.isHost
            );
            console.log('방장 여부:', isHost);
            console.log('참가자 수:', currentRoomInfo.participants.length);
        }
        
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
        currentRoomInfo = null;
        isGameStarted = false; // ❗ 추가: 게임 상태 초기화
        clearChatMessages();
        updateUserInfo();
        loadRooms();
        
        // ❗ 추가: 버튼 상태 업데이트
        updateGameButtons();
    } catch (error) {
        alert(error.message);
    }
}

function subscribeToRoom(roomId) {
    console.log('subscribeToRoom 함수 시작');
    console.log('stompClient:', stompClient);
    console.log('stompClient.connected:', stompClient?.connected);
    
    if (!stompClient || !stompClient.connected) {
        console.error('WebSocket이 연결되지 않았습니다. 구독을 건너뜁니다.');
        return;
    }
    
    const destination = `/topic/room.${roomId}`;
    console.log('구독할 destination:', destination);
    
    currentRoomSubscription = stompClient.subscribe(destination, (message) => {
        const chatMessage = JSON.parse(message.body);
        console.log('받은 메시지:', chatMessage);
        
        // ❗ 수정: 시스템 메시지 구분 처리
        if (chatMessage.senderId === 'SYSTEM') {
            addMessage(chatMessage, 'system');
        } else {
            const messageType = chatMessage.senderId === currentUser.userLoginId ? 'self' : 'other';
            addMessage(chatMessage, messageType);
        }
    });
    console.log(`✅ 성공적으로 구독됨: ${destination}`);
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

// ❗ 추가: 게임 시작 함수
async function startGame() {
    if (!currentRoom) {
        alert('방에 입장해주세요.');
        return;
    }
    
    // ❗ 추가: 4명 이상 확인
    if (!currentRoomInfo || !currentRoomInfo.participants) {
        alert('방 정보를 불러올 수 없습니다.');
        return;
    }
    
    const participantCount = currentRoomInfo.participants.length;
    if (participantCount < 4) {
        alert(`게임을 시작하려면 최소 4명이 필요합니다. (현재 ${participantCount}명)`);
        return;
    }
    
    // ❗ 추가: 방장 확인
    const isHost = currentRoomInfo.participants.some(p => 
        p.userLoginId === currentUser.userLoginId && p.isHost
    );
    if (!isHost) {
        alert('방장만 게임을 시작할 수 있습니다.');
        return;
    }
    
    try {
        // 게임 생성 요청
        const createGameResponse = await fetch('/api/game/create', {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json', 
                'Authorization': jwtToken 
            },
            body: JSON.stringify({
                roomId: currentRoom,
                players: currentRoomInfo.participants || [],
                maxPlayers: currentRoomInfo.maxPlayers || 8,
                hasDoctor: true,
                hasPolice: true
            })
        });
        
        if (!createGameResponse.ok) {
            throw new Error('게임 생성에 실패했습니다.');
        }
        
        const createResult = await createGameResponse.json();
        if (!createResult.success) {
            throw new Error(createResult.message || '게임 생성에 실패했습니다.');
        }
        
        const gameId = createResult.gameId;
        
        // 게임 시작 요청
        const startGameResponse = await fetch('/api/game/start', {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json', 
                'Authorization': jwtToken 
            },
            body: JSON.stringify({ gameId: gameId })
        });
        
        if (!startGameResponse.ok) {
            throw new Error('게임 시작에 실패했습니다.');
        }
        
        const startResult = await startGameResponse.json();
        if (!startResult.success) {
            throw new Error(startResult.message || '게임 시작에 실패했습니다.');
        }
        
        alert('게임이 시작되었습니다!');
        
        // ❗ 추가: 게임 시작 상태 업데이트
        isGameStarted = true;
        updateGameButtons();
        
    } catch (error) {
        console.error('게임 시작 실패:', error);
        alert('게임 시작에 실패했습니다: ' + error.message);
    }
}

// ❗ 추가: 버튼 표시/숨김 관리 함수
function updateGameButtons() {
    const createRoomBtn = document.getElementById('createRoomBtn');
    const startGameBtn = document.getElementById('startGameBtn');
    const leaveRoomBtn = document.getElementById('leaveRoomBtn');
    
    // 새 방 만들기 버튼: currentRoom이 없을 때만 표시
    if (createRoomBtn) {
        if (currentRoom) {
            createRoomBtn.style.display = 'none';
        } else {
            createRoomBtn.style.display = 'inline-block';
        }
    }
    
    // 게임 시작 버튼: currentRoom이 있고 방장인 경우만 표시, 4명 이상일 때만 활성화
    if (startGameBtn) {
        if (currentRoom && currentRoomInfo) {
            const isHost = currentRoomInfo.participants?.some(p => 
                p.userLoginId === currentUser.userLoginId && p.isHost
            );
            const participantCount = currentRoomInfo.participants?.length || 0;
            const canStartGame = participantCount >= 4;
            
            if (isHost) {
                startGameBtn.style.display = 'inline-block';
                startGameBtn.disabled = !canStartGame;
                
                // 버튼 텍스트 업데이트
                if (canStartGame) {
                    startGameBtn.textContent = '게임 시작';
                } else {
                    startGameBtn.textContent = `게임 시작 (${participantCount}/4명)`;
                }
            } else {
                startGameBtn.style.display = 'none';
            }
        } else {
            startGameBtn.style.display = 'none';
        }
    }
    
    // 방 나가기 버튼: currentRoom이 있고 게임이 시작되지 않았을 때만 표시
    if (leaveRoomBtn) {
        if (currentRoom && !isGameStarted) {
            leaveRoomBtn.style.display = 'inline-block';
        } else {
            leaveRoomBtn.style.display = 'none';
        }
    }
}

// ❗ 추가: 방 목록 새로고침 함수
async function refreshRoomList() {
    const refreshBtn = document.getElementById('refreshBtn');
    const refreshIcon = refreshBtn.querySelector('.refresh-icon');
    const refreshText = refreshBtn.querySelector('.refresh-text');
    
    // 현재 시간 확인
    const currentTime = Date.now();
    const timeSinceLastRefresh = currentTime - lastRefreshTime;
    const minWaitTime = 3000; // 3초
    
    // 최소 대기시간 확인
    if (timeSinceLastRefresh < minWaitTime) {
        const remainingTime = Math.ceil((minWaitTime - timeSinceLastRefresh) / 1000);
        alert(`새로고침은 ${remainingTime}초 후에 가능합니다.`);
        return;
    }
    
    try {
        // 버튼 비활성화 및 로딩 상태
        refreshBtn.disabled = true;
        refreshBtn.classList.add('loading');
        refreshText.textContent = '새로고침 중...';
        
        console.log('방 목록 새로고침 시작...');
        
        // 방 목록 로드
        await loadRooms();
        
        // ❗ 추가: 버튼 상태 업데이트
        updateGameButtons();
        
        // 마지막 새로고침 시간 업데이트
        lastRefreshTime = currentTime;
        
        console.log('방 목록 새로고침 완료');
        
        // 성공 메시지 (선택사항)
        const roomList = document.getElementById('roomList');
        if (roomList && roomList.children.length > 0) {
            console.log(`${roomList.children.length}개의 방이 로드되었습니다.`);
        }
        
    } catch (error) {
        console.error('방 목록 새로고침 중 오류:', error);
        alert('방 목록 새로고침에 실패했습니다.');
    } finally {
        // 버튼 상태 복원
        refreshBtn.disabled = false;
        refreshBtn.classList.remove('loading');
        refreshText.textContent = '새로고침';
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
            
            // ❗ 추가: 초기 로드 시 버튼 상태 업데이트
            updateGameButtons();
        } catch (e) {
            console.error("Failed to parse user data from localStorage", e);
            localStorage.clear();
        }
    }
};