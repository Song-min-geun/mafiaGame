// 전역 변수
let stompClient = null;
let currentRoom = null;
let currentUser = null;
let currentRoomInfo = null;
let jwtToken = null;
let currentRoomSubscription = null;
let lastRefreshTime = 0; // ❗ 추가: 마지막 새로고침 시간
let isGameStarted = false; // ❗ 추가: 게임 시작 상태
let isTokenExpired = false; // ❗ 추가: 토큰 만료 상태

// 게임 타이머 관련 변수들
let gameTimer = null;
let currentGameId = null;
let timeExtensionUsed = false;

// 투표 관련 변수들
let selectedVoteTarget = null;
let selectedNightActionTarget = null;
let currentGame = null;
let isPlayerDead = false; // ❗ 추가: 플레이어 생존 상태

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
        const userResponse = await apiRequest('/api/users/me');
        const userResult = await userResponse.json();
        if (!userResult.success) throw new Error(userResult.message || '사용자 정보 조회 실패');
        currentUser = userResult.data;
        localStorage.setItem('jwtToken', jwtToken);
        localStorage.setItem('currentUser', JSON.stringify(currentUser));
        document.getElementById('loginForm').classList.add('hidden');
        document.getElementById('registerForm').classList.add('hidden');
        document.getElementById('gameScreen').classList.remove('hidden');
        connectWebSocket();
        await loadRooms();
        updateUserInfo();
        updateGameButtons();
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
        stompClient.disconnect();
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
    if (stompClient && stompClient.connected) {
        return;
    }
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    
    const token = jwtToken ? jwtToken.replace('Bearer ', '') : null;
    if (!token) {
        return;
    }
    
    stompClient.connect({ 'Authorization': 'Bearer ' + token },
        frame => {
            document.getElementById('connectionStatus').textContent = '연결됨';
        },
        error => {
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
            method: 'GET',
            headers: { 'Authorization': jwtToken } 
        });


        if (response.status === 401) {
            // 인증 실패 시 로그아웃
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
        
        
    } catch (error) {
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

        const systemMessage = {
            type: 'CREATE',
            roomId: currentRoom,
            senderId: 'SYSTEM',
            senderName: '시스템',
            content: (currentUser.nickname || currentUser.userLoginId || '사용자') + '님이 방을 개설하였습니다.',
            timestamp: Date.now()
        };
        addMessage(systemMessage, 'system');
        
        // ❗ 추가: WebSocket 연결 확인 및 재연결
        if (!stompClient || !stompClient.connected) {
            connectWebSocket();
            // 연결 완료까지 잠시 대기
            await new Promise(resolve => setTimeout(resolve, 1000));
        }
        // ❗ 추가: 방 구독
        subscribeToRoom(currentRoom);
        updateUserInfo();
        // ❗ 추가: 버튼 상태 업데이트
        updateGameButtons();
        await loadRooms();
        
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
        
        subscribeToRoom(currentRoom);
        clearChatMessages();
        
        // ❗ 제거: 중복 시스템 메시지 추가 제거 (서버에서 WebSocket으로 전송됨)
        
        updateUserInfo();
        await loadRooms();
        
        // ❗ 추가: 버튼 표시/숨김 로직 (이미 최신 정보이므로 즉시 업데이트)
        updateGameButtons();
        
        
        // ❗ 추가: WebSocket을 통해 방 입장 시스템 메시지 전송
        if (stompClient && stompClient.connected) {
            const joinPayload = {
                roomId: roomId
            };
            stompClient.send("/app/room.join", {}, JSON.stringify(joinPayload));
        }
    } catch (error) {
        alert(error.message);
    }
}

async function leaveRoom() {
    if (!currentRoom) {
        return;
    }
    
    try {
        // ❗ 수정: WebSocket만 사용 (REST API 호출 제거)
        if (stompClient && stompClient.connected) {
            const leavePayload = {
                roomId: currentRoom
            };
            stompClient.send("/app/room.leave", {}, JSON.stringify(leavePayload));
        } else {
            throw new Error('WebSocket 연결이 없습니다.');
        }
        
        unsubscribeFromRoom();
        currentRoom = null;
        currentRoomInfo = null;
        isGameStarted = false; // ❗ 추가: 게임 상태 초기화
        
        // ❗ 추가: 타이머 정지
        stopGameTimer();
        
        clearChatMessages();
        updateUserInfo();
        await loadRooms();
        
        // ❗ 추가: 버튼 상태 업데이트
        updateGameButtons();
    } catch (error) {
        alert(error.message);
    }
}

function subscribeToRoom(roomId) {
    
    if (!stompClient || !stompClient.connected) {
        return;
    }
    
    const destination = `/topic/room.${roomId}`;
    
    // 개인 메시지 구독 추가 - 역할별 조건부 구독
    stompClient.subscribe('/user/queue/night-action', function(message) {
        const actionMessage = JSON.parse(message.body);
        if (actionMessage.gameId === currentGameId) {
            addMessage(actionMessage, 'system');
        }
    });
    
    // 역할별 구독은 게임 시작 후 setupRoleBasedSubscriptions()에서 설정
    
    currentRoomSubscription = stompClient.subscribe(destination, (message) => {
        const chatMessage = JSON.parse(message.body);
        
        // ❗ 수정: 구조화된 메시지 타입별 처리
        switch (chatMessage.type) {
            case 'USER_JOINED':
                // 서버가 보내준 '진짜' 데이터로 로컬 상태를 덮어쓴다
                if (chatMessage.data) {
                    currentRoomInfo.participants = chatMessage.data.participants;
                    currentRoomInfo.participantCount = chatMessage.data.participantCount;
                    currentRoomInfo.hostId = chatMessage.data.hostId;
                    currentRoomInfo.maxPlayers = chatMessage.data.maxPlayers;
                }
                
                // 새로운 데이터로 화면을 다시 그린다
                updateGameButtons();
                
                // 화면에 보여줄 시스템 메시지를 추가한다
                addMessage(chatMessage, 'system');
                break;
                
            case 'USER_LEFT':
                // 서버가 보내준 '진짜' 데이터로 로컬 상태를 덮어쓴다
                if (chatMessage.data) {
                    currentRoomInfo.participants = chatMessage.data.participants;
                    currentRoomInfo.participantCount = chatMessage.data.participantCount;
                    currentRoomInfo.hostId = chatMessage.data.hostId;
                    currentRoomInfo.maxPlayers = chatMessage.data.maxPlayers;
                }
                
                // 새로운 데이터로 화면을 다시 그린다
                updateGameButtons();
                
                // 화면에 보여줄 시스템 메시지를 추가한다
                addMessage(chatMessage, 'system');
                break;
                
            case 'CHAT':
                const messageType = chatMessage.senderId === currentUser.userLoginId ? 'self' : 'other';
                addMessage(chatMessage, messageType);
                break;
                
            case 'GAME_START':
                // 게임 시작 상태 업데이트
                isGameStarted = true;
                currentGameId = chatMessage.gameId;
                
                // 게임 시작 후 역할별 개인 메시지 구독 설정
                setupRoleBasedSubscriptions();
                
                // ❗ 추가: currentGame 초기화
                currentGame = {
                    gameId: chatMessage.gameId,
                    roomId: chatMessage.roomId,
                    players: chatMessage.players || [],
                    status: chatMessage.status,
                    currentPhase: chatMessage.currentPhase || 1,
                    isDay: chatMessage.isDay !== undefined ? chatMessage.isDay : true,  // ❗ 수정: 낮으로 시작
                    dayTimeLimit: chatMessage.dayTimeLimit || 60,
                    nightTimeLimit: chatMessage.nightTimeLimit || 30,
                    remainingTime: chatMessage.remainingTime || 60  // ❗ 수정: 낮 시간으로 시작
                };
                
                // 게임 UI 업데이트
                updateGameUI(currentGame);
                
                // 버튼 상태 업데이트
                updateGameButtons();
                
                // 타이머 시작
                startGameTimer();
                break;
                
            case 'GAME_END':
                // 게임 종료 상태 업데이트
                isGameStarted = false;
                currentGameId = null;
                isPlayerDead = false; // ❗ 추가: 죽은 플레이어 상태 초기화
                
                // 게임 종료 시스템 메시지 추가
                const gameEndMessage = {
                    type: 'SYSTEM',
                    content: '게임이 종료되었습니다.',
                    timestamp: new Date().toISOString()
                };
                addMessage(gameEndMessage, 'system');
                
                // UI 초기화
                hideAllGameUI();
                stopGameTimer();
                updateGameButtons();
                
                // 채팅 입력창 재활성화
                const messageInput = document.getElementById('messageInput');
                const sendButton = document.getElementById('sendButton');
                if (messageInput) {
                    messageInput.disabled = false;
                    messageInput.placeholder = '메시지를 입력하세요...';
                }
                if (sendButton) {
                    sendButton.disabled = false;
                    sendButton.textContent = '전송';
                }
                break;
                
            case 'TIMER_UPDATE':
                // 서버 타이머 업데이트 메시지 처리
                if (chatMessage.gameId === currentGameId) {
                    currentGame.remainingTime = chatMessage.remainingTime;
                    currentGame.gamePhase = chatMessage.gamePhase;
                    currentGame.currentPhase = chatMessage.currentPhase;
                    currentGame.isDay = chatMessage.isDay;
                    updateTimerDisplay(currentGame);
                }
                break;

            case 'TIME_EXTEND':
                // 시간 연장 메시지 처리
                if (chatMessage.gameId === currentGameId) {
                    currentGame.remainingTime = chatMessage.remainingTime;
                    updateTimerDisplay(currentGame);
                    
                    // 시스템 메시지로 시간 연장 알림
                    const timeMessage = {
                        type: 'SYSTEM',
                        senderId: 'SYSTEM',
                        content: `⏰ ${chatMessage.playerName}님이 시간을 ${chatMessage.seconds}초 연장했습니다.`,
                        timestamp: new Date().toISOString()
                    };
                    addMessage(timeMessage, 'system');
                }
                break;
                
            case 'TIME_REDUCE':
                // 시간 감소 메시지 처리
                if (chatMessage.gameId === currentGameId) {
                    currentGame.remainingTime = chatMessage.remainingTime;
                    updateTimerDisplay(currentGame);
                    
                    // 시스템 메시지로 시간 감소 알림
                    const timeMessage = {
                        type: 'SYSTEM',
                        senderId: 'SYSTEM',
                        content: `⏰ ${chatMessage.playerName}님이 시간을 ${chatMessage.seconds}초 단축했습니다.`,
                        timestamp: new Date().toISOString()
                    };
                    addMessage(timeMessage, 'system');
                }
                break;
                
            case 'VOTE_RESULT_UPDATE':
                // 투표 결과 업데이트 처리 (최다 득표자 선정)
                if (chatMessage.gameId === currentGameId) {
                    currentGame.players = chatMessage.players;
                    
                    // 최다 득표자 정보 저장
                    if (chatMessage.eliminatedPlayerId) {
                        currentGame.votedPlayerId = chatMessage.eliminatedPlayerId;
                        currentGame.votedPlayerName = chatMessage.eliminatedPlayerName;
                    }
                    
                    // 투표 UI 업데이트
                    updateGameUI(currentGame);
                }
                break;
                
            case 'FINAL_VOTE_RESULT_UPDATE':
                // 최종 투표 결과 업데이트 처리
                if (chatMessage.gameId === currentGameId) {
                    currentGame.players = chatMessage.players;
                    
                    // 제거된 플레이어가 현재 사용자인지 확인
                    if (chatMessage.eliminatedPlayerId === currentUser.userLoginId) {
                        isPlayerDead = true;
                        showDeadPlayerUI();
                    }
                    
                    // 최종 투표 결과 메시지 표시
                    if (chatMessage.result === 'ELIMINATED') {
                        const finalMessage = {
                            type: 'SYSTEM',
                            senderId: 'SYSTEM',
                            content: `최종 투표 결과: ${chatMessage.eliminatedPlayerName}님이 제거되었습니다.`,
                            timestamp: new Date().toISOString()
                        };
                        addMessage(finalMessage, 'system');
                    } else if (chatMessage.result === 'NOT_ELIMINATED') {
                        const finalMessage = {
                            type: 'SYSTEM',
                            senderId: 'SYSTEM',
                            content: '최종 투표 결과: 아무도 제거되지 않았습니다.',
                            timestamp: new Date().toISOString()
                        };
                        addMessage(finalMessage, 'system');
                    }
                    
                    // 투표 UI 업데이트
                    updateGameUI(currentGame);
                }
                break;
                
                
            case 'ROLE_ASSIGNED':
                // 개인 역할 배정 메시지 처리
                if (chatMessage.playerId === currentUser.userLoginId) {
                    const roleMessage = {
                        type: 'SYSTEM',
                        senderId: 'SYSTEM',
                        content: `당신의 역할: ${chatMessage.role} - ${chatMessage.roleDescription}`,
                        timestamp: new Date().toISOString()
                    };
                    addMessage(roleMessage, 'system');
                }
                break;
                
                
            case 'GAME_ENDED':
                // 게임 종료 메시지 처리
                if (chatMessage.gameId === currentGameId) {
                    const gameEndMessage = {
                        type: 'SYSTEM',
                        senderId: 'SYSTEM',
                        content: `🎉 ${chatMessage.message}`,
                        timestamp: chatMessage.timestamp
                    };
                    addMessage(gameEndMessage, 'system');
                    
                    // 게임 UI 숨기기
                    hideAllGameUI();
                    
                    // 게임 종료 상태로 설정
                    isGameStarted = false;
                    currentGame = null;
                    currentGameId = null;
                }
                break;
                
            case 'ROLE_DISTRIBUTION':
                // 역할 분포 공개 메시지 처리
                const roleCounts = chatMessage.roleCounts;
                let distributionText = "역할 분포: ";
                if (roleCounts.MAFIA > 0) distributionText += `마피아 ${roleCounts.MAFIA}명 `;
                if (roleCounts.DOCTOR > 0) distributionText += `의사 ${roleCounts.DOCTOR}명 `;
                if (roleCounts.POLICE > 0) distributionText += `경찰 ${roleCounts.POLICE}명 `;
                if (roleCounts.CITIZEN > 0) distributionText += `시민 ${roleCounts.CITIZEN}명`;
                
                const distributionMessage = {
                    type: 'SYSTEM',
                    senderId: 'SYSTEM',
                    content: distributionText,
                    timestamp: new Date().toISOString()
                };
                addMessage(distributionMessage, 'system');
                break;
                
            case 'PHASE_SWITCHED':
                // 페이즈 전환 메시지 처리
                if (chatMessage.gameId === currentGameId) {
                    currentGame.gamePhase = chatMessage.gamePhase;
                    currentGame.currentPhase = chatMessage.currentPhase;
                    currentGame.isDay = chatMessage.isDay;
                    currentGame.remainingTime = chatMessage.remainingTime;
                    
                    // 플레이어 데이터 업데이트 (중요!)
                    if (chatMessage.players) {
                        currentGame.players = chatMessage.players;
                    }
                    
                    // 게임 UI 업데이트
                    updateGameUI(currentGame);
                    updateTimerDisplay(currentGame);
                    
                    // 투표 페이즈인 경우 추가 로그
                    if (chatMessage.gamePhase === 'DAY_VOTING' || chatMessage.gamePhase === 'DAY_FINAL_VOTE') {
                        
                    // 투표 페이즈로 전환 시 시간 연장 기회 초기화
                    if (chatMessage.gamePhase === 'DAY_VOTING') {
                        timeExtensionUsed = false;
                    }
                    
                    // 낮 대화 페이즈로 전환 시 시간 연장 기회 초기화
                    if (chatMessage.gamePhase === 'DAY_DISCUSSION') {
                        timeExtensionUsed = false;
                        // 시간 연장/단축 버튼 활성화
                        const extendBtn = document.getElementById('extendTimeBtn');
                        const reduceBtn = document.getElementById('reduceTimeBtn');
                        if (extendBtn) extendBtn.disabled = false;
                        if (reduceBtn) reduceBtn.disabled = false;
                    }
                        
                        // 강제로 투표 UI 표시 시도
                        setTimeout(() => {
                            showVotingUI(currentGame);
                        }, 100);
                    }
                    
                    // 페이즈 전환 시스템 메시지 표시
                    addMessage(chatMessage, 'system');
                }
                break;
                
            default:
                // 기타 메시지 타입 처리
                if (chatMessage.senderId === 'SYSTEM') {
                    addMessage(chatMessage, 'system');
                } else {
                    const messageType = chatMessage.senderId === currentUser.userLoginId ? 'self' : 'other';
                    addMessage(chatMessage, messageType);
                }
                break;
        }
    });
}

function unsubscribeFromRoom() {
    if (currentRoomSubscription) {
        currentRoomSubscription.unsubscribe();
        currentRoomSubscription = null;
    }
}

//채팅방에서의 채팅 보내기
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

// ❗ 추가: 죽은 플레이어 UI 표시
function showDeadPlayerUI() {
    // 채팅 입력창 비활성화
    const messageInput = document.getElementById('messageInput');
    const sendButton = document.getElementById('sendButton');
    
    if (messageInput) {
        messageInput.disabled = true;
        messageInput.placeholder = '죽은 플레이어는 채팅할 수 없습니다.';
    }
    
    if (sendButton) {
        sendButton.disabled = true;
        sendButton.textContent = '죽음';
    }
    
    // 죽은 플레이어 안내 메시지 표시
    const deadPlayerMessage = {
        type: 'SYSTEM',
        senderId: 'SYSTEM',
        content: '당신은 죽었습니다. 게임이 끝날 때까지 기다려주세요.',
        timestamp: new Date().toISOString()
    };
    addMessage(deadPlayerMessage, 'system');
    
    // 투표 UI 숨기기
    hideAllGameUI();
    
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
    
    try {
        // ❗ 수정: 서버가 기대하는 데이터 형식으로 변환
        const players = (currentRoomInfo.participants || []).map(participant => ({
            playerId: participant.userId,      // userId -> playerId
            playerName: participant.userName,  // userName -> playerName
            isHost: participant.isHost || false  // null/undefined 방지
        }));

        const gameData = {
            roomId: currentRoom,
            players: players,
            maxPlayers: currentRoomInfo.maxPlayers || 8,
            hasDoctor: true,
            hasPolice: true
        };
        
        // 게임 생성 요청
        const createGameResponse = await fetch('/api/game/create', {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json', 
                'Authorization': jwtToken 
            },
            body: JSON.stringify(gameData)
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
        
        // ❗ 수정: 게임 시작 상태 업데이트 (알림 전에)
        isGameStarted = true;
        currentGameId = gameId;
        
        // ❗ 추가: currentGame 초기화 (방장용)
        currentGame = {
            gameId: gameId,
            roomId: currentRoom.roomId,
            players: players,
            status: 'STARTING',
            currentPhase: 1,
            isDay: true,  // ❗ 수정: 낮으로 시작
            dayTimeLimit: 60,
            nightTimeLimit: 30,
            remainingTime: 60  // ❗ 수정: 낮 시간으로 시작
        };
        
        // 게임 UI 업데이트
        updateGameUI(currentGame);
        
        // 버튼 상태 업데이트
        updateGameButtons();
        
        // 시간 연장/단축 버튼 활성화
        const extendBtn = document.getElementById('extendTimeBtn');
        const reduceBtn = document.getElementById('reduceTimeBtn');
        if (extendBtn) extendBtn.disabled = false;
        if (reduceBtn) reduceBtn.disabled = false;
        
        // 타이머 시작
        startGameTimer();
        
        
    } catch (error) {
        console.error('게임 시작 실패:', error);
        alert('게임 시작에 실패했습니다: ' + error.message);
    }
}

// ❗ 추가: 게임 타이머 시작
function startGameTimer() {
    const gameTimerElement = document.getElementById('gameTimer');
    if (gameTimerElement) {
        gameTimerElement.style.display = 'block';
    }
    
    // 타이머 업데이트 시작
    updateGameTimer()
}

// ❗ 추가: 게임 타이머 업데이트
function updateGameTimer() {
    if (currentGame && isGameStarted) {
        updateTimerDisplay(currentGame);
    }
}

// ❗ 추가: 타이머 표시 업데이트
function updateTimerDisplay(game) {
    const timerLabel = document.getElementById('timerLabel');
    const timerCountdown = document.getElementById('timerCountdown');
    const extendButtons = document.querySelectorAll('.timer-controls button');

    if(!isGameStarted){
        gameTimer.display = 'none';
    }

    if (timerLabel && timerCountdown) {
        // 게임 페이즈에 따른 표시
        let phaseText = '';
        switch (game.gamePhase) {
            case 'DAY_DISCUSSION':
                phaseText = `${game.currentPhase}일째 낮 대화`;
                break;
            case 'DAY_VOTING':
                phaseText = `${game.currentPhase}일째 투표`;
                timeExtensionUsed = true;
                break;
            case 'DAY_FINAL_DEFENSE':
                phaseText = `${game.currentPhase}일째 최후의 반론`;
                timeExtensionUsed = true;
                break;
            case 'DAY_FINAL_VOTE':
                phaseText = `${game.currentPhase}일째 찬성/반대`;
                timeExtensionUsed = true;
                break;
            case 'NIGHT_ACTION':
                phaseText = `${game.currentPhase}일째 밤 액션`;
                timeExtensionUsed = true;
                break;
            default:
                phaseText = game.isDay ? '낮' : '밤';
        }
        timerLabel.textContent = phaseText;
        
        // 남은 시간 표시
        const remainingTime = game.remainingTime || 0;
        timerCountdown.textContent = remainingTime;
        
        // 경고 상태 (10초 이하)
        if (remainingTime <= 10) {
            timerCountdown.classList.add('warning');
        } else {
            timerCountdown.classList.remove('warning');
        }
        
        // 시간 연장 버튼 활성화/비활성화
        const canExtend = !timeExtensionUsed && remainingTime > 0;
        extendButtons.forEach(button => {
            button.disabled = !canExtend;
        });
    }
    
    // ❗ 추가: 게임 상태에 따른 UI 업데이트
    currentGame = game;
    updateGameUI(game);
}

// ❗ 수정: 시간 연장/단축
async function extendTime(seconds) {
    if (!currentGameId || !currentUser || !currentGame) {
        alert('게임 정보를 찾을 수 없습니다.');
        return;
    }
    
    if (timeExtensionUsed) {
        alert('이미 시간 연장/단축을 사용했습니다.');
        return;
    }
    
    try {
        const response = await fetch('/api/game/extend-time', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': jwtToken
            },
            body: JSON.stringify({
                gameId: currentGameId,
                playerId: currentUser.userLoginId,
                seconds: seconds
            })
        });
        
        const result = await response.json();
        
        if (result.success) {
            timeExtensionUsed = true;
            
            // 버튼 비활성화
            const extendBtn = document.getElementById('extendTimeBtn');
            const reduceBtn = document.getElementById('reduceTimeBtn');
            if (extendBtn) extendBtn.disabled = true;
            if (reduceBtn) reduceBtn.disabled = true;
            
            // ❗ 수정: 서버에서 업데이트된 게임 상태 사용
            if (result.game) {
                currentGame = result.game;
                updateTimerDisplay(currentGame);
            }
            // 시간 연장 성공 - WebSocket 메시지로 처리됨
        } else {
            alert(result.message || '시간 연장/단축에 실패했습니다.');
        }
        
    } catch (error) {
        console.error('시간 연장/단축 실패:', error);
        alert('시간 연장/단축에 실패했습니다.');
    }
}

// ❗ 추가: 역할별 개인 메시지 구독 설정
function setupRoleBasedSubscriptions() {
    if (!stompClient || !currentGame) {
        return;
    }
    
    // 현재 플레이어의 역할 확인
    const currentPlayer = currentGame.players ? 
        currentGame.players.find(p => p.playerId === currentUser.userLoginId) : null;
    
    if (!currentPlayer) {
        return;
    }
    
    // 경찰 역할인 경우에만 경찰 메시지 구독
    if (currentPlayer.role === 'POLICE') {
        stompClient.subscribe('/user/queue/police', function(message) {
            const investigationMessage = JSON.parse(message.body);
            if (investigationMessage.gameId === currentGameId) {
                addMessage(investigationMessage, 'system');
            }
        });
        console.log('경찰 역할로 경찰 메시지 구독 설정됨');
    }
}

// ❗ 추가: 페이즈 전환
async function switchPhase() {
    if (!currentGameId) return;
    
    try {
        const response = await fetch('/api/game/switch-phase', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': jwtToken
            },
            body: JSON.stringify({ gameId: currentGameId })
        });
        
        const result = await response.json();
        if (result.success && result.game) {
            currentGame = result.game;
            updateGameUI(currentGame);
            updateTimerDisplay(currentGame);
        }
    } catch (error) {
        console.error('페이즈 전환 실패:', error);
    }
}

// ❗ 추가: 타이머 정지
function stopGameTimer() {
    if (gameTimer) {
        clearTimeout(gameTimer);
        gameTimer = null;
    }
    
    const gameTimerElement = document.getElementById('gameTimer');
    if (gameTimerElement) {
        gameTimerElement.style.display = 'none';
    }
    
    timeExtensionUsed = false;
    currentGameId = null;
}

// ❗ 추가: 게임 UI 업데이트
function updateGameUI(game) {
    if (!game || !currentUser) {
        return;
    }
    
    const currentPlayer = game.players.find(p => p.playerId === currentUser.userLoginId);
    if (!currentPlayer) {
        hideAllGameUI();
        return;
    }
    
    // isAlive 필드가 undefined인 경우 true로 기본값 설정
    if (currentPlayer.isAlive === false) {
        hideAllGameUI();
        return;
    }
    
    
    // 현재 UI 상태 저장
    const currentVotingArea = document.getElementById('votingArea');
    const currentNightActionArea = document.getElementById('nightActionArea');
    const currentVotedPlayerInfo = document.getElementById('votedPlayerInfo');
    
    const isVotingVisible = currentVotingArea && currentVotingArea.style.display !== 'none';
    const isNightActionVisible = currentNightActionArea && currentNightActionArea.style.display !== 'none';
    const isVotedPlayerInfoVisible = currentVotedPlayerInfo && currentVotedPlayerInfo.style.display !== 'none';
    
    // 게임 페이즈에 따라 UI 표시
    switch (game.gamePhase) {
        case 'DAY_DISCUSSION':
            // 낮 대화 - 투표 UI 숨김
            if (isVotingVisible || isNightActionVisible || isVotedPlayerInfoVisible) {
                hideAllGameUI();
            }
            break;
        case 'DAY_VOTING':
            // 낮 투표 - 모든 생존자 투표 가능
            if (!isVotingVisible) {
                showVotingUI(game);
            }
            break;
        case 'DAY_FINAL_DEFENSE':
            // 최후의 반론 - 투표 UI 숨김
            if (isVotingVisible || isNightActionVisible) {
                hideAllGameUI();
            }
            break;
        case 'DAY_FINAL_VOTE':
            // 최종 투표 - 찬성/반대 투표 UI 표시
            if (!isVotingVisible && !isVotedPlayerInfoVisible) {
                showFinalVoteUI(game);
            }
            break;
        case 'NIGHT_ACTION':
            // 밤 액션 - 특수 역할만 액션 가능
            if (!isNightActionVisible) {
                showNightActionUI(game, currentPlayer);
            }
            break;
        default:
            hideAllGameUI();
    }
}

// ❗ 추가: 투표 UI 표시
function showVotingUI(game) {
    
    const votingArea = document.getElementById('votingArea');
    const nightActionArea = document.getElementById('nightActionArea');
    
    if (votingArea) {
        votingArea.style.display = 'block';
        
        // 채팅 메시지 영역을 아래로 이동
        const chatMessages = document.getElementById('chatMessages');
        if (chatMessages) {
            chatMessages.style.marginTop = '220px';
        }
    } else {
    }
    
    if (nightActionArea) {
        nightActionArea.style.display = 'none';
    }
    
    // 투표 설명 업데이트
    const votingDescription = document.getElementById('votingDescription');
    if (votingDescription) {
        if (game.gamePhase === 'DAY_VOTING') {
            votingDescription.textContent = '제거할 플레이어를 선택하세요';
        } else if (game.gamePhase === 'DAY_FINAL_VOTE') {
            votingDescription.textContent = '최종 투표: 제거할 플레이어를 선택하세요';
        }
    } else {
    }
    
    
    // 투표 대상 플레이어 목록 생성
    const votingOptions = document.getElementById('votingOptions');
    if (votingOptions) {
        // 이미 투표 옵션이 있으면 재생성하지 않음
        if (votingOptions.children.length > 0) {
            return;
        }
        
        votingOptions.innerHTML = '';
        
        // 생존한 플레이어들만 표시
        
        const alivePlayers = game.players ? game.players.filter(player => {
            // isAlive가 undefined인 경우 true로 기본값 설정
            return player.isAlive !== undefined ? player.isAlive : true;
        }) : [];
        
        // 자기 자신을 제외한 생존 플레이어들만 필터링
        
        const voteablePlayers = alivePlayers.filter(player => {
            const isNotCurrentUser = player.playerId !== currentUser.userLoginId;
            return isNotCurrentUser;
        });
        
        // 임시로 모든 생존 플레이어를 투표 옵션으로 표시 (디버깅용)
        if (alivePlayers.length === 0) {
            const noVoteOption = document.createElement('div');
            noVoteOption.className = 'voting-option disabled';
            noVoteOption.textContent = '생존한 플레이어가 없습니다';
            votingOptions.appendChild(noVoteOption);
            return;
        }
        
        // 임시로 자기 자신 제외 조건을 제거하고 모든 생존 플레이어 표시
        alivePlayers.forEach(player => {
            const option = document.createElement('div');
            option.className = 'voting-option';
            option.textContent = player.playerName + (player.playerId === currentUser.userLoginId ? ' (나)' : '');
            option.dataset.playerId = player.playerId;
            option.onclick = () => selectVoteTarget(player.playerId);
            votingOptions.appendChild(option);
        });
    }
    
    // 투표 상태 초기화
    selectedVoteTarget = null;
    updateVoteButtons();
}

// ❗ 추가: 밤 액션 UI 표시
function showNightActionUI(game, currentPlayer) {
    const votingArea = document.getElementById('votingArea');
    const nightActionArea = document.getElementById('nightActionArea');
    
    if (votingArea) votingArea.style.display = 'none';
    if (nightActionArea) nightActionArea.style.display = 'block';
    
    // 역할에 따른 액션 설정
    const title = document.getElementById('nightActionTitle');
    const description = document.getElementById('nightActionDescription');
    const options = document.getElementById('nightActionOptions');
    
    if (title && description && options) {
        switch (currentPlayer.role) {
            case 'MAFIA':
                title.textContent = '마피아 액션';
                description.textContent = '제거할 플레이어를 선택하세요';
                break;
            case 'DOCTOR':
                title.textContent = '의사 액션';
                description.textContent = '치료할 플레이어를 선택하세요';
                break;
            case 'POLICE':
                title.textContent = '경찰 액션';
                description.textContent = '조사할 플레이어를 선택하세요';
                break;
            default:
                title.textContent = '밤 시간';
                description.textContent = '특수 역할이 아닙니다';
                break;
        }
        
        // 액션 대상 플레이어 목록 생성
        options.innerHTML = '';
        
        if (currentPlayer.role !== 'CITIZEN') {
            game.players.forEach(player => {
                // 의사는 자기 자신도 치료할 수 있음
                const canSelectSelf = currentPlayer.role === 'DOCTOR';
                const isSelf = player.playerId === currentUser.userLoginId;
                
                if (player.isAlive && (canSelectSelf || !isSelf)) {
                    const option = document.createElement('div');
                    option.className = 'night-action-option';
                    option.textContent = player.playerName;
                    option.dataset.playerId = player.playerId;
                    option.onclick = () => selectNightActionTarget(player.playerId);
                    options.appendChild(option);
                }
            });
        }
    }
}

// ❗ 추가: 최종 투표 UI 표시 (찬성/반대)
function showFinalVoteUI(game) {
    
    const votingArea = document.getElementById('votingArea');
    if (!votingArea) {
        return;
    }
    
    // 최다 득표자(변론자)는 투표할 수 없음
    if (game.votedPlayerId === currentUser.userLoginId) {
        
        // 투표 영역 숨기기
        votingArea.style.display = 'none';
        
        // 최다 득표자 안내 UI 표시
        const votedPlayerInfo = document.getElementById('votedPlayerInfo');
        if (votedPlayerInfo) {
            votedPlayerInfo.style.display = 'block';
        }
        
        // 채팅 메시지 영역을 아래로 이동
        const chatMessages = document.getElementById('chatMessages');
        if (chatMessages) {
            chatMessages.style.marginTop = '220px';
        }
        
        return;
    }
    
    // 최다 득표자가 아닌 경우 최다 득표자 안내 UI 숨기기
    const votedPlayerInfo = document.getElementById('votedPlayerInfo');
    if (votedPlayerInfo) {
        votedPlayerInfo.style.display = 'none';
    }
    
    // 투표 영역 표시
    votingArea.style.display = 'block';
    
    // 투표 설명 설정
    const votingDescription = document.getElementById('votingDescription');
    if (votingDescription) {
        votingDescription.textContent = `최종 투표: ${game.votedPlayerName}님에 대한 찬성 또는 반대를 선택하세요`;
        votingDescription.style.color = '#333';
        votingDescription.style.fontWeight = 'normal';
    }
    
    // 찬성/반대 버튼 생성
    const votingOptions = document.getElementById('votingOptions');
    if (votingOptions) {
        votingOptions.innerHTML = '';
        
        // 찬성 버튼
        const agreeButton = document.createElement('button');
        agreeButton.textContent = '찬성';
        agreeButton.className = 'voting-option';
        agreeButton.onclick = () => {
            // 선택 상태 표시
            agreeButton.classList.add('selected');
            disagreeButton.classList.remove('selected');
            
            // 버튼 비활성화
            agreeButton.disabled = true;
            disagreeButton.disabled = true;
            
            submitFinalVote('AGREE');
        };
        
        // 반대 버튼
        const disagreeButton = document.createElement('button');
        disagreeButton.textContent = '반대';
        disagreeButton.className = 'voting-option';
        disagreeButton.onclick = () => {
            // 선택 상태 표시
            disagreeButton.classList.add('selected');
            agreeButton.classList.remove('selected');
            
            // 버튼 비활성화
            agreeButton.disabled = true;
            disagreeButton.disabled = true;
            
            submitFinalVote('DISAGREE');
        };
        
        votingOptions.appendChild(agreeButton);
        votingOptions.appendChild(disagreeButton);
        
    }
    
}

// ❗ 추가: 최종 투표 제출
function submitFinalVote(vote) {
    
    if (!currentGame || !currentUser) {
        return;
    }
    
    
    // WebSocket으로 투표 전송
    if (stompClient && stompClient.connected) {
        const voteMessage = {
            type: 'FINAL_VOTE',
            gameId: currentGameId,
            roomId: currentRoom,
            playerId: currentUser.userLoginId,
            vote: vote
        };
        
        stompClient.send('/app/game.vote', {}, JSON.stringify(voteMessage));
    }
}

// ❗ 추가: 모든 게임 UI 숨기기
function hideAllGameUI() {
    const votingArea = document.getElementById('votingArea');
    const nightActionArea = document.getElementById('nightActionArea');
    const votedPlayerInfo = document.getElementById('votedPlayerInfo');
    
    if (votingArea) votingArea.style.display = 'none';
    if (nightActionArea) nightActionArea.style.display = 'none';
    if (votedPlayerInfo) votedPlayerInfo.style.display = 'none';
    
    // 채팅 메시지 영역을 원래 위치로 복원
    const chatMessages = document.getElementById('chatMessages');
    if (chatMessages) {
        chatMessages.style.marginTop = '0px';
    }
}

// ❗ 추가: 투표 대상 선택
function selectVoteTarget(playerId) {
    selectedVoteTarget = playerId;
    
    // 모든 옵션에서 선택 상태 제거
    document.querySelectorAll('.voting-option').forEach(option => {
        option.classList.remove('selected');
    });
    
    // 선택된 옵션에 선택 상태 추가
    const selectedOption = document.querySelector(`[data-player-id="${playerId}"]`);
    if (selectedOption) {
        selectedOption.classList.add('selected');
    }
    
    // 투표 버튼 상태 업데이트
    updateVoteButtons();
}

// ❗ 추가: 밤 액션 대상 선택
function selectNightActionTarget(playerId) {
    selectedNightActionTarget = playerId;
    
    // 모든 옵션에서 선택 상태 제거
    document.querySelectorAll('.night-action-option').forEach(option => {
        option.classList.remove('selected');
    });
    
    // 선택된 옵션에 선택 상태 추가
    const selectedOption = document.querySelector(`[data-player-id="${playerId}"]`);
    if (selectedOption) {
        selectedOption.classList.add('selected');
    }
    
    // 액션 버튼 활성화
    const submitBtn = document.getElementById('submitNightActionBtn');
    if (submitBtn) {
        submitBtn.disabled = false;
    }
}

// ❗ 추가: 투표 버튼 상태 업데이트
function updateVoteButtons() {
    const submitBtn = document.getElementById('submitVoteBtn');
    const cancelBtn = document.getElementById('cancelVoteBtn');
    
    if (submitBtn) {
        submitBtn.disabled = !selectedVoteTarget;
    }
    
    if (cancelBtn) {
        cancelBtn.disabled = !selectedVoteTarget;
    }
}

// ❗ 추가: 투표 제출
async function submitVote() {
    if (!selectedVoteTarget || !currentGameId || !currentUser) {
        alert('투표 대상을 선택해주세요.');
        return;
    }
    
    try {
        const response = await apiRequest('/api/game/vote', {
            method: 'POST',
            body: JSON.stringify({
                gameId: currentGameId,
                voterId: currentUser.userLoginId,
                targetId: selectedVoteTarget
            })
        });
        
        const result = await response.json();
        
        if (result.success) {
            alert('투표가 완료되었습니다.');
            selectedVoteTarget = null;
            
            // 투표 UI 초기화
            document.querySelectorAll('.voting-option').forEach(option => {
                option.classList.remove('selected');
            });
            
            const submitBtn = document.getElementById('submitVoteBtn');
            if (submitBtn) {
                submitBtn.disabled = true;
            }
        } else {
            alert(result.message || '투표에 실패했습니다.');
        }
        
    } catch (error) {
        console.error('투표 실패:', error);
        alert('투표에 실패했습니다.');
    }
}

// ❗ 추가: 투표 취소
function cancelVote() {
    selectedVoteTarget = null;
    
    document.querySelectorAll('.voting-option').forEach(option => {
        option.classList.remove('selected');
    });
    
    const submitBtn = document.getElementById('submitVoteBtn');
    if (submitBtn) {
        submitBtn.disabled = true;
    }
}

// ❗ 추가: 밤 액션 제출
async function submitNightAction() {
    if (!selectedNightActionTarget || !currentGameId || !currentUser) {
        alert('대상을 선택해주세요.');
        return;
    }
    
    try {
        const response = await fetch('/api/game/night-action', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': jwtToken
            },
            body: JSON.stringify({
                gameId: currentGameId,
                playerId: currentUser.userLoginId,
                targetId: selectedNightActionTarget
            })
        });
        
        const result = await response.json();
        
        if (result.success) {
            alert('밤 액션이 완료되었습니다.');
            selectedNightActionTarget = null;
            
            // 액션 UI 초기화
            document.querySelectorAll('.night-action-option').forEach(option => {
                option.classList.remove('selected');
            });
            
            const submitBtn = document.getElementById('submitNightActionBtn');
            if (submitBtn) {
                submitBtn.disabled = true;
            }
        } else {
            alert(result.message || '밤 액션에 실패했습니다.');
        }
        
    } catch (error) {
        console.error('밤 액션 실패:', error);
        alert('밤 액션에 실패했습니다.');
    }
}

// ❗ 추가: 밤 액션 취소
function cancelNightAction() {
    selectedNightActionTarget = null;
    
    document.querySelectorAll('.night-action-option').forEach(option => {
        option.classList.remove('selected');
    });
    
    const submitBtn = document.getElementById('submitNightActionBtn');
    if (submitBtn) {
        submitBtn.disabled = true;
    }
}

// ❗ 추가: 버튼 표시/숨김 관리 함수
function updateGameButtons() {//355
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
    
    // 게임 시작 버튼: 방장이면 항상 표시, 4명 이상일 때만 활성화
    if (startGameBtn) {
        if (currentRoom && currentRoomInfo) {
            // ✅ 수정: hostId와 userLoginId 직접 비교
            const isHost = currentRoomInfo.hostId === currentUser.userLoginId;
            const participantCount = currentRoomInfo.participantCount || currentRoomInfo.participants?.length || 0;
            const canStartGame = participantCount >= 4;
            
            if (isHost) {
                // 방장이면 항상 버튼 표시
                startGameBtn.style.display = 'inline-block';
                startGameBtn.disabled = !canStartGame;
                
                // 버튼 텍스트 업데이트
                if (canStartGame) {
                    startGameBtn.textContent = '게임 시작';
                    startGameBtn.title = '게임을 시작합니다';
                } else {
                    startGameBtn.textContent = `게임 시작 (${participantCount}/4명)`;
                    startGameBtn.title = `최소 4명이 필요합니다 (현재 ${participantCount}명)`;
                }
                
            } else {
                // 방장이 아니면 버튼 숨김
                startGameBtn.style.display = 'none';

            }
        } else {
            startGameBtn.style.display = 'none';
        }
    }
    
    // 현재 방과 게임 시작전 나기가, 게임시작 버튼 사라짐
    if (leaveRoomBtn) {
        if (currentRoom && !isGameStarted) {
            leaveRoomBtn.style.display = 'inline-block';
        } else {
            leaveRoomBtn.style.display = 'none';
            startGameBtn.style.display = 'none';
        }
    }
}


// ❗ 추가: 현재 방 정보 갱신 함수 (서버 요청용 - 백업)
async function updateCurrentRoomInfo() {
    if (!currentRoom) return;
    
    try {
        const response = await fetch(`/api/chat/rooms/${currentRoom}`, {
            method: 'GET',
            headers: { 'Authorization': jwtToken }
        });
        
        if (response.ok) {
            const roomData = await response.json();
            currentRoomInfo = roomData;
            
            // 버튼 상태 업데이트
            updateGameButtons();
        }
    } catch (error) {
        console.error('방 정보 갱신 실패:', error);
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
        
        
        // 방 목록 로드
        await loadRooms();
        
        // ❗ 추가: 버튼 상태 업데이트
        updateGameButtons();
        
        // 마지막 새로고침 시간 업데이트
        lastRefreshTime = currentTime;
        
        
        // 성공 메시지 (선택사항)
        const roomList = document.getElementById('roomList');
        if (roomList && roomList.children.length > 0) {
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

// ❗ 추가: 토큰 만료 처리 함수
async function handleTokenExpiration() {
    if (isTokenExpired) return; // 이미 처리 중이면 중복 실행 방지
    
    isTokenExpired = true;
    
    // 로그아웃 처리
    logout();
    
    // 사용자에게 알림
    alert('세션이 만료되었습니다. 다시 로그인해주세요.');
    
    // 로그인 화면으로 이동
    document.getElementById('loginForm').classList.remove('hidden');
    document.getElementById('registerForm').classList.add('hidden');
    document.getElementById('gameScreen').classList.add('hidden');
    
    // WebSocket 연결 해제
    if (stompClient) {
        stompClient.disconnect();
        stompClient = null;
    }
    
    // 전역 변수 초기화
    currentRoom = null;
    currentUser = null;
    currentRoomInfo = null;
    jwtToken = null;
    currentRoomSubscription = null;
    isGameStarted = false;
    currentGameId = null;
    currentGame = null;
    
    // 로컬 스토리지 정리
    localStorage.removeItem('jwtToken');
    localStorage.removeItem('currentUser');
}

// ❗ 추가: API 요청 래퍼 함수 (토큰 만료 처리 포함)
async function apiRequest(url, options = {}) {
    try {
        const response = await fetch(url, {
            ...options,
            headers: {
                'Content-Type': 'application/json',
                'Authorization': jwtToken,
                ...options.headers
            }
        });
        
        // 401 Unauthorized 응답 시 토큰 만료 처리
        if (response.status === 401) {
            await handleTokenExpiration();
            throw new Error('인증이 필요합니다. 다시 로그인해주세요.');
        }
        
        return response;
    } catch (error) {
        if (error.message.includes('인증이 필요합니다')) {
            throw error;
        }
        throw new Error('네트워크 오류가 발생했습니다: ' + error.message);
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
}