// 전역 변수
let stompClient = null;
let currentRoom = null;
let currentUser = null;
let jwtToken = null; // 로그인 시 받은 JWT 토큰을 저장할 변수

// 로그인 폼 표시
function showLogin() {
    document.getElementById('loginForm').classList.remove('hidden');
    document.getElementById('registerForm').classList.add('hidden');
}

// 회원가입 폼 표시
function showRegister() {
    document.getElementById('loginForm').classList.add('hidden');
    document.getElementById('registerForm').classList.remove('hidden');
}

// 로그인
async function login() {
    const userLoginId = document.getElementById('userLoginId').value;
    const userLoginPassword = document.getElementById('userLoginPassword').value;
    
    try {
        const response = await fetch('/api/users/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ 
                userLoginId, 
                userLoginPassword 
            })
        });
        
                    const result = await response.json();

            if (!result.success) {
                alert(result.message || '로그인에 실패했습니다.');
            } else {
                // 새로운 API 응답 형식에 맞춤
                const token = result.data.token;
                
                // 로그인 성공 후 사용자 상세 정보 조회
                try {
                    const userResponse = await fetch('/api/users/me', {
                        headers: {
                            'Authorization': 'Bearer ' + token
                        }
                    });
                    
                    if (userResponse.ok) {
                        const userResult = await userResponse.json();
                        if (userResult.success) {
                            currentUser = {
                                userId: userResult.data.userId,
                                userLoginId: userResult.data.userLoginId,
                                nickname: userResult.data.nickname
                            };
                        } else {
                            // 사용자 정보 조회 실패 시 기본값 사용
                            currentUser = {
                                userId: null,
                                userLoginId: document.getElementById('userLoginId').value,
                                nickname: document.getElementById('userLoginId').value
                            };
                        }
                    } else {
                        // API 호출 실패 시 기본값 사용
                        currentUser = {
                            userId: null,
                            userLoginId: document.getElementById('userLoginId').value,
                            nickname: document.getElementById('userLoginId').value
                        };
                    }
                } catch (error) {
                    console.error('사용자 정보 조회 실패:', error);
                    currentUser = {
                        userId: null,
                        userLoginId: document.getElementById('userLoginId').value,
                        nickname: document.getElementById('userLoginId').value
                    };
                }
                
                jwtToken = 'Bearer ' + token;
                localStorage.setItem('jwtToken', jwtToken);
                localStorage.setItem('currentUser', JSON.stringify(currentUser));
                

            
            document.getElementById('loginForm').classList.add('hidden');
            document.getElementById('gameScreen').classList.remove('hidden');
            
            connectWebSocket();
            loadRooms();
            updateUserInfo();
        }
    } catch (error) {
        alert('로그인 중 오류가 발생했습니다.');
    }
}

// 회원가입
async function register() {
    const userLoginId = document.getElementById('regUserLoginId').value;
    const userLoginPassword = document.getElementById('regUserLoginPassword').value;
    const nickname = document.getElementById('regNickname').value;
    
    try {
        const response = await fetch('/api/users/register', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ 
                userLoginId, 
                userLoginPassword, 
                nickname,
                userRole: 'USER' 
            })
        });
        
                    const result = await response.json();
            
            if (result.success) {
                alert(result.message || '회원가입이 완료되었습니다.');
                showLogin();
            } else{
                alert(result.message || '회원가입에 실패했습니다.');
            }
    } catch (error) {
        alert('회원가입 중 오류가 발생했습니다.');
    }
}

// 로그인 상태 확인
function checkLoginStatus() {
    const savedUser = localStorage.getItem('currentUser');
    const savedRoom = localStorage.getItem('currentRoom');

    if (savedUser) {
        try {
            currentUser = JSON.parse(savedUser);
            // 저장된 JWT 토큰이 있으면 헤더에 추가
            const savedToken = localStorage.getItem('jwtToken');
            if (savedToken) {
                jwtToken = savedToken;
            }
            
            // 저장된 방 정보 복구
            if (savedRoom) {
                try {
                    const roomInfo = JSON.parse(savedRoom);
                    currentRoom = roomInfo.roomId;
                } catch (error) {
                    localStorage.removeItem('currentRoom');
                }
            }
            
            document.getElementById('loginForm').classList.add('hidden');
            document.getElementById('gameScreen').classList.remove('hidden');
            
            connectWebSocket();
            loadRooms();
            updateUserInfo();
            
            // 방이 있으면 방 정보 표시
            if (currentRoom) {
                loadRoomInfo(currentRoom);
            }
            
            return true;
        } catch (error) {
            localStorage.removeItem('currentUser');
            localStorage.removeItem('jwtToken');
            localStorage.removeItem('currentRoom');
        }
    }
    return false;
}

// 사용자 정보 업데이트
function updateUserInfo() {
    if (currentUser) {
        // nickname을 대표 이름으로 표시
        const displayName = currentUser.nickname || currentUser.userLoginId;
        document.getElementById('currentUserName').textContent = displayName;
    }
    
    // 방 상태 표시
    updateRoomStatus();
}

// 방 상태 표시 업데이트
function updateRoomStatus() {
    const roomStatusElement = document.getElementById('currentRoomStatus');
    if (roomStatusElement) {
        if (currentRoom) {
            const subscriptionStatus = window.currentRoomSubscription ? ' (구독됨)' : ' (구독 안됨)';
            roomStatusElement.textContent = currentRoom + subscriptionStatus;
            roomStatusElement.style.color = window.currentRoomSubscription ? '#27ae60' : '#e74c3c';
        } else {
            roomStatusElement.textContent = '없음';
            roomStatusElement.style.color = '#95a5a6';
        }
    }
}

// 방 구독 함수
function subscribeToRoom(roomId) {
    console.log('=== subscribeToRoom 함수 시작 ===');
    console.log('구독 시도 정보:', {
        roomId: roomId,
        stompClient: stompClient,
        isConnected: stompClient ? stompClient.connected : false,
        currentUser: currentUser
    });
    
    if (!stompClient || !stompClient.connected) {
        console.error('WebSocket not connected!');
        return false;
    }
    
    // 기존 구독 해제
    if (window.currentRoomSubscription) {
        console.log('기존 구독 해제:', window.currentRoomSubscription);
        window.currentRoomSubscription.unsubscribe();
        window.currentRoomSubscription = null;
    }
    
    // 개인 큐로 방 구독
    const userId = currentUser.userId || currentUser.userLoginId;
    const subscriptionPath = `/user/queue/room.${roomId}`;
    console.log('구독 경로:', subscriptionPath);
    
    const subscription = stompClient.subscribe(subscriptionPath, function (message) {
        console.log('메시지 수신:', {
            message: message,
            body: message.body,
            destination: message.destination
        });
        
        try {
            const chatMessage = JSON.parse(message.body);
            console.log('파싱된 메시지:', chatMessage);
            addMessage(chatMessage, 'other');
        } catch (error) {
            console.error('JSON 파싱 오류:', error);
        }
    });
    
    // 구독 정보 저장
    window.currentRoomSubscription = subscription;
    console.log('구독 성공:', {
        subscription: subscription,
        subscriptionId: subscription.id,
        subscriptionPath: subscriptionPath
    });
    
    return true;
}

// 401 오류 처리 (인증 실패)
function handleAuthError() {
    console.warn('Authentication failed (401), logging out...');
    alert('인증이 만료되었습니다. 다시 로그인해주세요.');
    logout();
}

// JWT 토큰 유효성 검사
function isJwtTokenValid() {
    if (!jwtToken) {
        return false;
    }
    
    // Bearer 접두사 확인
    if (!jwtToken.startsWith('Bearer ')) {
        return false;
    }
    
    // 토큰 길이 확인 (최소 100자)
    if (jwtToken.length < 100) {
        return false;
    }
    
    return true;
}

// 로그아웃
function logout() {
    // ❗ 수정: 로그아웃이 여러 번 호출되는 것을 방지
    if (!currentUser) return;

    console.log("Logging out...");
    currentUser = null;
    currentRoom = null;
    jwtToken = null;
    localStorage.removeItem('currentUser');
    localStorage.removeItem('jwtToken');
    localStorage.removeItem('currentRoom');

    // ❗ 수정: WebSocket 연결 해제를 더 안전하게 처리
    if (stompClient) {
        // stompClient가 존재하고, 실제로 연결된(connected) 상태일 때만 disconnect 호출
        if (stompClient.connected) {
            stompClient.disconnect(() => {
                console.log("WebSocket disconnected successfully.");
            });
        }
        stompClient = null;
    }

    document.getElementById('gameScreen').classList.add('hidden');
    document.getElementById('loginForm').classList.remove('hidden');
    updateUserInfo();

    // 폼 초기화
    document.getElementById('userLoginId').value = '';
    document.getElementById('userLoginPassword').value = '';
}

// WebSocket 연결
function connectWebSocket() {
    try {
        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);
        
        // ❗ 수정: 로그인 시 저장된 순수 토큰을 가져옵니다.
        // jwtToken 변수에 'Bearer ' 접두사가 포함되어 있으므로 제거합니다.
        const token = jwtToken ? jwtToken.replace('Bearer ', '') : null;

        if (!token) {
            console.error('JWT Token not found. Cannot connect to WebSocket.');
            return;
        }

        // ❗ 수정: STOMP 연결 헤더에 Authorization 토큰을 포함시킵니다.
        // STOMP.js는 이 방식의 헤더 전달을 표준으로 지원합니다.
        const connectHeaders = {
            'Authorization': 'Bearer ' + token
        };
        
        // ❗ 추가: 연결 헤더 검증 로그
        console.log('연결 시도 정보:', {
            token: token ? token.substring(0, 20) + '...' : 'null',
            tokenLength: token ? token.length : 0,
            connectHeaders: connectHeaders,
            socket: socket,
            stompClient: stompClient
        });
        
        // ❗ 추가: 10초 타임아웃 설정
        const connectionTimeout = setTimeout(() => {
            alert('Connection timeout. Please check your connection or try again.');
            socket.close();
        }, 10000);
        
        stompClient.connect(connectHeaders, function (frame) {
            // ❗ 추가: 연결 성공 시 타임아웃 타이머를 취소
            clearTimeout(connectionTimeout);
            
            // ❗ 추가: 디버깅을 위한 stompClient 상태 로그
            console.log('WebSocket 연결 성공, stompClient 상태:', {
                stompClient: stompClient,
                isNull: stompClient === null,
                isUndefined: stompClient === undefined,
                type: typeof stompClient,
                frame: frame
            });
            
            // ❗ 추가: 연결 직후 stompClient 상태 재확인
            setTimeout(() => {
                console.log('연결 직후 stompClient 상태 재확인:', {
                    stompClient: stompClient,
                    isNull: stompClient === null,
                    isConnected: stompClient ? stompClient.connected : false
                });
            }, 100);
            
            // 연결 상태를 UI에 표시
            if (document.getElementById('connectionStatus')) {
                document.getElementById('connectionStatus').textContent = '연결됨';
                document.getElementById('connectionStatus').style.color = 'green';
            }
        
        // ❗ 수정: stompClient가 null인지 확인하는 안전장치 추가
        if (stompClient) {
            console.log('구독 설정 시작, stompClient 상태:', {
                stompClient: stompClient,
                isConnected: stompClient.connected,
                subscriptions: Object.keys(stompClient.subscriptions || {})
            });
            
            // 개인 메시지 구독
            stompClient.subscribe('/user/queue/role', function (message) {
                const chatMessage = JSON.parse(message.body);
                addMessage(chatMessage, 'system');
            });
            
            // 에러 메시지 구독
            stompClient.subscribe('/user/queue/error', function (message) {
                const chatMessage = JSON.parse(message.body);
                addMessage(chatMessage, 'system');
            });
            
            // 전역 채팅 구독
            stompClient.subscribe('/topic/public', function (message) {
                const chatMessage = JSON.parse(message.body);
                addMessage(chatMessage, chatMessage.type.toLowerCase());
            });
            
                    // 현재 방이 있으면 다시 구독
            if (currentRoom) {
                console.log('현재 방 재구독 시도:', currentRoom);
                // ❗ 수정: 구독을 비동기로 처리
                setTimeout(() => {
                    if (stompClient && stompClient.connected) {
                        subscribeToRoom(currentRoom);
                    } else {
                        console.error('구독 시도 시 stompClient가 연결되지 않음');
                    }
                }, 200);
            }
            
            // ❗ 추가: 구독 상태 확인 로그
            console.log('WebSocket 연결 완료, 구독 상태:', {
                stompClient: stompClient,
                currentRoom: currentRoom,
                subscriptions: stompClient ? Object.keys(stompClient.subscriptions || {}) : []
            });
        } else {
            console.error('stompClient is null during subscription setup');
        }
    }, function (error) {
        // ❗ 추가: 연결 실패 시 타임아웃 타이머를 취소
        clearTimeout(connectionTimeout);
        
        // ❗ 추가: 연결 실패 상세 정보 로깅
        console.error('WebSocket 연결 실패 상세 정보:', {
            error: error,
            errorMessage: error.message,
            errorType: error.type,
            errorCode: error.code,
            stompClient: stompClient,
            socket: socket,
            connectHeaders: connectHeaders
        });
        
        // 연결 상태 업데이트
        if (document.getElementById('connectionStatus')) {
            document.getElementById('connectionStatus').textContent = '연결 실패';
            document.getElementById('connectionStatus').style.color = 'red';
        }
        
        // 연결 실패 시 재시도
        if (!window.reconnectAttempts) {
            window.reconnectAttempts = 0;
        }
        
        if (window.reconnectAttempts < 3) {
            window.reconnectAttempts++;
            setTimeout(connectWebSocket, 3000);
        } else {
            alert('연결이 끊어졌습니다. 페이지를 새로고침해주세요.');
        }
    });
            } catch (error) {
            console.error('WebSocket 연결 중 예외 발생:', error);
        }
}

// 채팅룸 목록 로드
async function loadRooms() {
    try {
        // JWT 토큰 유효성 검사
        if (!isJwtTokenValid()) {
            console.error('JWT 토큰이 유효하지 않습니다.');
            const roomList = document.getElementById('roomList');
            roomList.innerHTML = '<div class="room-item">로그인이 필요합니다.</div>';
            return;
        }
        
        const response = await fetch('/api/chat/rooms', {
            headers: {
                'Authorization': jwtToken
            }
        });
        
        if (response.status === 401) {
            // 인증 실패 시 로그아웃
            logout();
            return;
        }
        
        if (response.status === 403) {
            // 권한 없음
            alert('권한이 없습니다. 다시 로그인해주세요.');
            logout();
            return;
        }
        
        if (!response.ok) {
            alert(`오류가 발생했습니다: ${response.status}`);
            return;
        }
        
        const rooms = await response.json();
        
        const roomList = document.getElementById('roomList');
        roomList.innerHTML = '';
        
        if (rooms.length === 0) {
            roomList.innerHTML = '<div class="room-item">현재 생성된 방이 없습니다.</div>';
            return;
        }
        
        rooms.forEach(room => {
            const roomItem = document.createElement('div');
            roomItem.className = 'room-item';
            roomItem.innerHTML = `
                <strong>${room.roomName}</strong><br>
                참가자: ${room.participants ? room.participants.length : 0}/${room.maxPlayers}
                ${room.isGameActive ? '<br><span style="color: #e74c3c;">게임 진행 중</span>' : ''}
            `;
            roomItem.onclick = () => joinRoom(room.roomId);
            roomList.appendChild(roomItem);
        });
    } catch (error) {
        console.error('채팅룸 목록을 불러오는데 실패했습니다:', error);
        const roomList = document.getElementById('roomList');
        roomList.innerHTML = '<div class="room-item">방 목록을 불러오는데 실패했습니다.<br><small>에러: ' + error.message + '</small></div>';
    }
}

// 채팅룸 입장
async function joinRoom(roomId) {
    try {
        // userId가 없으면 userLoginId 사용
        const userId = currentUser.userId || currentUser.userLoginId;
        const userName = currentUser.nickname || currentUser.userLoginId;
        
        if (!userId) {
            alert('사용자 정보를 가져올 수 없습니다. 다시 로그인해주세요.');
            return;
        }
        
        const response = await fetch(`/api/chat/rooms/${roomId}/join`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': jwtToken
            },
            body: JSON.stringify({ 
                userId: userId.toString(), 
                userName: userName
            })
        });
        
        if (response.status === 401) {
            // 인증 실패 시 로그아웃
            logout();
            return;
        }
        
        if (response.ok) {
            currentRoom = roomId;
            // 방 입장 시 현재 방 정보를 localStorage에 저장
            localStorage.setItem('currentRoom', JSON.stringify({ roomId: currentRoom }));
            
            // 채팅 메시지 초기화
            clearChatMessages();
            
            // WebSocket으로 방 구독
            if (!subscribeToRoom(roomId)) {
                console.error('Failed to subscribe to room!');
                alert('방 구독에 실패했습니다. 다시 시도해주세요.');
                return;
            }
            
                            // 방 정보 로드
                loadRoomInfo(roomId);
                
                // 방 상태 업데이트
                updateRoomStatus();
            
            // 방 이름 가져오기
            let roomName = '알 수 없는 방';
            try {
                const roomResponse = await fetch(`/api/chat/rooms/${roomId}`, {
                    headers: {
                        'Authorization': jwtToken
                    }
                });
                if (roomResponse.ok) {
                    const room = await roomResponse.json();
                    roomName = room.roomName;
                }
            } catch (error) {
                console.error('방 이름을 가져오는데 실패했습니다:', error);
            }
            
            // WebSocket을 통해 입장 메시지 전송 (다른 사용자들에게 알림)
            if (stompClient && stompClient.connected) {
                stompClient.send("/app/room.join", {}, JSON.stringify({
                    roomId: roomId,
                    userId: userId.toString(),
                    userName: userName,
                    type: 'JOIN'
                }));
                
                // 입장 성공 메시지 추가 (방 이름 포함)
                addMessage({
                    senderName: '시스템',
                    content: `${roomName}에 ${userName}님이 입장하였습니다.`,
                    type: 'JOIN',
                    timestamp: Date.now()
                }, 'join');
                

            } else {
                console.error('WebSocket not connected!');
            }
            
            // 방 목록 새로고침
            loadRooms();
        } else {
            alert('방 입장에 실패했습니다.');
        }
    } catch (error) {
        alert('방 입장에 실패했습니다.');
        console.error('Error joining room:', error);
    }
}

// 방 나가기
async function leaveRoom() {
    if (!currentRoom) {
        alert('현재 입장한 방이 없습니다.');
        return;
    }
    
    try {
        // userId가 없으면 userLoginId 사용
        const userId = currentUser.userId || currentUser.userLoginId;
        if (!userId) {
            alert('사용자 정보를 가져올 수 없습니다. 다시 로그인해주세요.');
            return;
        }
        
        const response = await fetch(`/api/chat/rooms/${currentRoom}/leave`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': jwtToken
            },
            body: JSON.stringify({
                userId: userId.toString()
            })
        });
        
        if (response.status === 401) {
            logout();
            return;
        }
        
        if (response.ok) {
            // WebSocket 구독 해제
            if (stompClient && currentRoom) {
                stompClient.unsubscribe(`/user/queue/room.${currentRoom}`);
            }
            
            // 구독 정보 정리
            if (window.currentRoomSubscription) {
                window.currentRoomSubscription.unsubscribe();
                window.currentRoomSubscription = null;
            }
            
            // 전역 구독 정보도 정리
            window.currentRoomSubscription = null;
            
            // 방 나가기 메시지 전송
            if (stompClient && stompClient.connected) {
                stompClient.send("/app/room.leave", {}, JSON.stringify({
                    roomId: currentRoom,
                    userId: userId.toString(),
                    userName: userName,
                    type: 'LEAVE'
                }));
                

            }
            
                            // 채팅 메시지 초기화
                clearChatMessages();
                
                // 현재 방 정보 초기화
                currentRoom = null;
                localStorage.removeItem('currentRoom');
                
                // 방 상태 업데이트
                updateRoomStatus();
                
                // 방 목록 새로고침
                loadRooms();
                
                alert('방을 나갔습니다.');
        } else {
            alert('방 나가기에 실패했습니다.');
        }
    } catch (error) {
        alert('방 나가기에 실패했습니다.');
        console.error('Error leaving room:', error);
    }
}

// 방 정보 로드
async function loadRoomInfo(roomId) {
    try {
        const response = await fetch(`/api/chat/rooms/${roomId}`, {
            headers: {
                'Authorization': jwtToken
            }
        });
        
        if (response.status === 401) {
            // 인증 실패 시 로그아웃
            console.error('❌ 401 Unauthorized - 인증 실패');
            logout();
            return;
        }
        
        if (response.status === 403) {
            // 권한 없음
            console.error('❌ 403 Forbidden - 권한 없음');
            console.error('Response text:', await response.text());
            alert('권한이 없습니다. 다시 로그인해주세요.');
            logout();
            return;
        }
        
        if (!response.ok) {
            console.error(`❌ HTTP ${response.status} 오류`);
            console.error('Response text:', await response.text());
            alert(`오류가 발생했습니다: ${response.status}`);
            return;
        }
        
        const room = await response.json();
        
        // 게임 시작 버튼 활성화/비활성화
        document.getElementById('startGameBtn').disabled = !room.canStartGame;
        document.getElementById('endGameBtn').disabled = !room.isGameActive;
    } catch (error) {
        console.error('방 정보를 불러오는데 실패했습니다:', error);
    }
}

// 새 방 만들기
async function createRoom() {
    const roomName = prompt('방 이름을 입력하세요:');
    if (!roomName) return;
    
    // userId가 없으면 userLoginId 사용
    const hostId = currentUser.userId || currentUser.userLoginId;
    if (!hostId) {
        alert('사용자 정보를 가져올 수 없습니다. 다시 로그인해주세요.');
        return;
    }
    
    try {
        const response = await fetch('/api/chat/rooms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': jwtToken
            },
            body: JSON.stringify({ 
                roomName, 
                hostId: hostId.toString(),
                maxPlayers: 8
            })
        });
        
        if (response.status === 401) {
            // 인증 실패 시 로그아웃
            logout();
            return;
        }
        
        if (response.ok) {
            const room = await response.json();
                    alert('방이 생성되었습니다!');
        joinRoom(room.roomId);
        loadRooms();
    } else {
        const error = await response.json();
        alert('방 생성에 실패했습니다: ' + (error.error || '알 수 없는 오류'));
    }
} catch (error) {
    alert('방 생성에 실패했습니다.');
}
}

// 게임 시작
async function startGame() {
    if (!currentRoom) return;
    
    try {
        const response = await fetch(`/api/chat/rooms/${currentRoom}/start-game`, {
            method: 'POST',
            headers: {
                'Authorization': jwtToken
            }
        });
        
        if (response.status === 401) {
            logout();
            return;
        }
        
        if (response.ok) {
            document.getElementById('startGameBtn').disabled = true;
            document.getElementById('endGameBtn').disabled = false;
            addMessage({
                senderName: '시스템',
                content: '게임이 시작되었습니다!'
            }, 'system');
        }
    } catch (error) {
        alert('게임 시작에 실패했습니다.');
    }
}

// 게임 종료
async function endGame() {
    if (!currentRoom) return;
    
    try {
        const response = await fetch(`/api/chat/rooms/${currentRoom}/end-game`, {
            method: 'POST',
            headers: {
                'Authorization': jwtToken
            }
        });
        
        if (response.status === 401) {
            logout();
            return;
        }
        
        if (response.ok) {
            document.getElementById('startGameBtn').disabled = false;
            document.getElementById('endGameBtn').disabled = true;
            addMessage({
                senderName: '시스템',
                content: '게임이 종료되었습니다.'
            }, 'system');
        }
    } catch (error) {
        alert('게임 종료에 실패했습니다.');
    }
}

// 메시지 전송
function sendMessage() {
    const messageInput = document.getElementById('messageInput');
    const message = messageInput.value.trim();

    console.log('=== sendMessage 함수 시작 ===');
    console.log('메시지 전송 시도:', {
        message: message,
        currentRoom: currentRoom,
        stompClient: stompClient,
        isConnected: stompClient ? stompClient.connected : false,
        currentRoomSubscription: window.currentRoomSubscription
    });

    if (message && currentRoom) {
        if (stompClient && stompClient.connected) {
            // 방 구독 상태 확인
            if (!window.currentRoomSubscription) {
                console.error('No room subscription found!');
                // 구독 재시도
                if (subscribeToRoom(currentRoom)) {
                    console.log('방 구독 재시도 성공');
                } else {
                    alert('방 구독에 실패했습니다. 방을 다시 입장해주세요.');
                    return;
                }
            }
            
            const senderId = currentUser.userLoginId; // senderId를 userLoginId로 통일
            
            if (!senderId) {
                alert('사용자 정보를 가져올 수 없습니다. 다시 로그인해주세요.');
                return;
            }
            
            const chatMessage = {
                type: 'CHAT',
                roomId: currentRoom,
                senderId: senderId,
                senderName: currentUser.nickname || senderId,
                content: message,
                timestamp: Date.now(),
            };
            
            console.log('전송할 메시지:', chatMessage);
            console.log('전송 경로:', "/app/chat.sendMessage");
            
            // ❗ 수정: 메시지 전송 시에는 STOMP 헤더를 보낼 필요가 없습니다.
            // 연결 시 사용된 인증 정보가 계속 유지됩니다.
            stompClient.send("/app/chat.sendMessage", {}, JSON.stringify(chatMessage));
            
            // 로컬에 메시지 즉시 표시 (본인 메시지)
            addMessage(chatMessage, 'self');
        } else {
            console.error('WebSocket not connected!');
            alert('연결이 끊어졌습니다. 페이지를 새로고침해주세요.');
        }
        messageInput.value = '';
    } else if (!currentRoom) {
        alert('먼저 방에 입장해주세요.');
    }
}

// 키 입력 처리
function handleKeyPress(event) {
    if (event.key === 'Enter') {
        sendMessage();
    }
}

// 메시지 추가
function addMessage(chatMessage, type) {
    const chatMessages = document.getElementById('chatMessages');
    
    if (!chatMessages) {
        return;
    }
    
    const messageDiv = document.createElement('div');
    
    // 메시지 타입에 따른 스타일 결정
    let messageType = 'other';
    if (type === 'self') {
        messageType = 'self';
    } else if (type === 'join' || type === 'leave') {
        messageType = type;
    }
    
    messageDiv.className = `message ${messageType}`;

    const time = new Date(chatMessage.timestamp || Date.now()).toLocaleTimeString();
    
    // 입장/퇴장 메시지와 일반 메시지 구분
    if (messageType === 'join' || messageType === 'leave') {
        messageDiv.innerHTML = `
            <small>${time}</small><br>
            ${chatMessage.content}
        `;
    } else {
        messageDiv.innerHTML = `
            <div class="message-content">${chatMessage.content}</div>
            <div class="message-time">${time}</div>
        `;
    }

    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

// 채팅 메시지 초기화
function clearChatMessages() {
    const chatMessages = document.getElementById('chatMessages');
    chatMessages.innerHTML = ''; // 기존 메시지를 모두 지웁니다.
}



// 페이지 로드 시 초기화
window.onload = function() {
    // 초기 상태 설정
    checkLoginStatus(); // 로그인 상태 확인
    
    // 채팅방 목록 자동 새로고침 시작
    startRoomListRefresh();
};

// 채팅방 목록 자동 새로고침 시작
function startRoomListRefresh() {
    // 1초마다 방 목록 새로고침
    setInterval(() => {
        if (currentUser && jwtToken) {
            loadRooms();
        }
    }, 5000);
}
