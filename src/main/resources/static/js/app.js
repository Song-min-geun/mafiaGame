// 전역 변수
let stompClient = null;
let currentRoom = null;
let currentUser = null;
let currentRoomInfo = null;
let jwtToken = null;
let currentRoomSubscription = null;
let lastRefreshTime = 0; // 마지막 새로고침 시간
let isGameStarted = false; // 게임 시작 상태
let isTokenExpired = false; // 토큰 만료 상태

// 게임 타이머 관련 변수들
let gameTimer = null;
let currentGameId = null;
let timeExtensionUsed = false;
let allRooms = []; // ❗ 추가: 모든 방 목록 저장

// ❗ 추가: 페이지 로드 시 로그인 체크
document.addEventListener('DOMContentLoaded', async () => {
    const storedToken = localStorage.getItem('jwtToken');
    const storedUser = localStorage.getItem('currentUser');

    if (storedToken && storedUser) {
        jwtToken = storedToken;
        currentUser = JSON.parse(storedUser);

        // 토큰 유효성 검증 (선택적)
        try {
            const response = await fetch('/api/users/me', {
                headers: { 'Authorization': jwtToken }
            });

            if (response.ok) {
                // 로그인 상태 복구
                document.getElementById('loginForm').classList.add('hidden');
                document.getElementById('registerForm').classList.add('hidden');
                document.getElementById('gameScreen').classList.remove('hidden');

                await connectWebSocket();
                await loadRooms();
                updateUserInfo();

                // 세션 복구 시도
                await restoreUserSession();
            } else {
                // 토큰 만료 또는 유효하지 않음
                throw new Error('Session expired');
            }
        } catch (error) {
            console.log('Session validation failed:', error);
            logout();
        }
    } else {
        // 로그인 정보가 없으면 로그인 화면 표시
        logout(); // 확실하게 초기화
    }
});

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

        // ❗ 수정: 로그인 직후 UI 즉시 업데이트 (웹소켓 연결 대기 전)
        updateUserInfo();
        document.getElementById('loginForm').classList.add('hidden');
        document.getElementById('registerForm').classList.add('hidden');
        document.getElementById('gameScreen').classList.remove('hidden');


        // WebSocket 연결 후 개인 메시지 구독 및 로비 구독 설정
        await connectWebSocket();

        // 로그인 시 새로고침 타이머 초기화 (즉시 새로고침 가능하도록)
        lastRefreshTime = 0;

        //await loadRooms();
        await refreshRoomList();
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

    // 역할 정보 UI 초기화
    const headerUserRole = document.getElementById('headerUserRole');
    if (headerUserRole) {
        headerUserRole.textContent = '';
        headerUserRole.style.display = 'none';
    }

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
    return new Promise((resolve, reject) => {
        if (stompClient && stompClient.connected) {
            resolve();
            return;
        }

        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);

        const token = jwtToken ? jwtToken.replace('Bearer ', '') : null;
        if (!token) {
            reject(new Error('JWT 토큰이 없습니다.'));
            return;
        }

        stompClient.connect({ 'Authorization': 'Bearer ' + token },
            frame => {
                const statusElem = document.getElementById('headerConnectionStatus');
                if (statusElem) statusElem.textContent = '🟢';
                console.log('WebSocket 연결 성공:', frame);
                console.log('현재 사용자:', currentUser);

                // 연결 성공 시 개인 메시지 구독
                subscribeToPrivateMessages();

                // ❗ 추가: 로비 구독 (방 목록 갱신)
                subscribeToLobby();
            },
            error => {
                const statusElem = document.getElementById('headerConnectionStatus');
                if (statusElem) statusElem.textContent = '🔴';
                console.error('WebSocket 연결 실패:', error);
                reject(error);
            }
        );
    });
}

// 개인 메시지 구독을 위한 함수
function subscribeToPrivateMessages() {
    // connect 콜백 내에서 호출되므로 connected 체크 제거 또는 로그 강화
    console.log('subscribeToPrivateMessages 호출됨. currentUser:', currentUser);

    if (!stompClient) {
        console.error('stompClient가 없습니다.');
        return;
    }

    const privateTopic = `/topic/private/${currentUser.userLoginId}`;
    console.log(`개인 메시지 구독 시도: ${privateTopic}`);

    stompClient.subscribe(privateTopic, (message) => {
        console.log('🔥 개인 메시지 수신됨 (RAW):', message);
        console.log('🔥 개인 메시지 바디:', message.body);
        const privateMessage = JSON.parse(message.body);
        console.log('🔥 개인 메시지 파싱 완료:', privateMessage);

        switch (privateMessage.type) {
            case 'ROLE_ASSIGNED':
                console.log('ROLE_ASSIGNED 메시지 수신:', privateMessage);

                const role = privateMessage.role || '알 수 없음';
                const roleDescription = privateMessage.roleDescription || '설명이 없습니다.';

                if (currentUser) {
                    currentUser.role = role;
                    currentUser.roleDescription = roleDescription;
                    console.log('currentUser 업데이트 완료:', currentUser);
                } else {
                    console.error('currentUser가 없습니다!');
                }
                updateUserInfo();

                addMessage({
                    senderId: 'SYSTEM',
                    content: `당신의 역할: ${role} - ${roleDescription}`
                }, 'system');
                break;
            case 'PRIVATE_MESSAGE':
                addMessage({
                    senderId: 'SYSTEM',
                    content: privateMessage.content
                }, 'system');
                break;
            case 'ERROR':
                alert(privateMessage.content);
                break;
            default:
                console.log('알 수 없는 개인 메시지 타입:', privateMessage.type);
                addMessage({
                    senderId: 'SYSTEM',
                    content: privateMessage.content || '개인 메시지'
                }, 'system');
                break;
        }
    });

    console.log('개인 메시지 구독 완료');
    console.log('개인 메시지 구독 완료');
}

// ❗ 추가: 로비 구독 함수
function subscribeToLobby() {
    if (!stompClient || !stompClient.connected) return;

    console.log('로비 구독 시작 (/topic/rooms)');
    stompClient.subscribe('/topic/rooms', (message) => {
        const roomUpdate = JSON.parse(message.body);
        if (roomUpdate.type === 'ROOM_LIST_UPDATED') {
            console.log('방 목록 갱신 신호 수신, 목록을 새로고침합니다.');
            loadRooms();
        }
    });
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

        allRooms = await response.json();
        filterAndSortRooms();

    } catch (error) {
        const roomList = document.getElementById('roomList');
        if (roomList) {
            roomList.innerHTML = '<div class="room-item error">방 목록을 불러올 수 없습니다.</div>';
        }
    }
}

// ❗ 추가: 방 목록 필터링 및 정렬 함수
function filterAndSortRooms() {
    const hidePlaying = document.getElementById('hidePlayingCheckbox').checked;
    const sortBy = document.getElementById('roomSortSelect').value;
    const roomList = document.getElementById('roomList');

    if (!roomList) return;

    let displayRooms = [...allRooms];

    // 1. 필터링 (진행중인 게임 숨기기)
    if (hidePlaying) {
        displayRooms = displayRooms.filter(room => !room.playing);
    }

    // 2. 정렬
    displayRooms.sort((a, b) => {
        // ❗ 추가: 현재 참여 중인 방을 최상단으로
        if (currentRoom) {
            if (a.roomId === currentRoom) return -1;
            if (b.roomId === currentRoom) return 1;
        }

        if (sortBy === 'countDesc') {
            const countA = a.participants ? a.participants.length : 0;
            const countB = b.participants ? b.participants.length : 0;
            // 인원수가 같으면 이름순
            if (countB !== countA) return countB - countA;
            return (a.roomName || '').localeCompare(b.roomName || '');
        } else if (sortBy === 'nameAsc') {
            return (a.roomName || '').localeCompare(b.roomName || '');
        }
        return 0;
    });

    // 3. 렌더링
    roomList.innerHTML = '';

    if (displayRooms.length === 0) {
        roomList.innerHTML = '<div class="room-item no-rooms">표시할 방이 없습니다.</div>';
        return;
    }

    displayRooms.forEach(room => {
        const roomItem = document.createElement('div');
        roomItem.className = 'room-item';
        if (room.playing) {
            roomItem.classList.add('playing');
        }

        const participantCount = room.participants ? room.participants.length : 0;
        const maxPlayers = room.maxPlayers || 8;
        const isCurrentRoom = currentRoom === room.roomId;
        let roomName = room.roomName || `방 ${room.roomId}`;

        // 진행중 상태 표시
        if (room.playing) {
            roomName = `[진행중] ${roomName}`;
        }

        // 현재 방 정보 업데이트 (여기서는 렌더링만 하므로 상태 업데이트는 최소화)
        if (isCurrentRoom && !currentRoomInfo) {
            currentRoomInfo = room;
            updateGameButtons();
        }

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




async function createRoom() {
    const roomName = prompt('방 이름을 입력하세요:');
    if (!roomName) return;
    try {
        const response = await fetch('/api/chat/rooms', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': jwtToken },
            body: JSON.stringify({ roomName, userId: currentUser.userLoginId })
        });

        if (!response.ok) throw new Error('방 생성 실패');
        const room = await response.json();

        // ❗ 수정: 방 생성 후 방 정보 설정 (자동 입장 제거)
        currentRoom = room.roomId;
        currentRoomInfo = room;

        // ✅ 추가: 방 생성 후 버튼 상태 업데이트
        updateGameButtons();

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
    if (currentRoom === roomId) {
        console.log('이미 해당 방에 참가 중입니다.');
        return;
    }
    if (currentRoom) {
        await leaveRoom(); // 이전 방에서 나감
    }

    try {
        currentRoom = roomId;
        currentRoomInfo = {}; // 방 정보 객체 초기화

        // 새 방의 토픽을 구독
        subscribeToRoom(roomId);
        clearChatMessages();

        // WebSocket을 통해 방 입장 메시지 전송
        if (stompClient && stompClient.connected) {
            stompClient.send("/app/room.join", {}, JSON.stringify({ roomId: roomId }));
        } else {
            // 연결이 끊겼을 경우 재연결 시도 후 메시지 전송
            await connectWebSocket();
            stompClient.send("/app/room.join", {}, JSON.stringify({ roomId: roomId }));
        }

        // ✅ 추가: 방 정보 조회 및 업데이트
        try {
            const roomResponse = await fetch(`/api/chat/rooms/${roomId}`, {
                method: 'GET',
                headers: { 'Authorization': jwtToken }
            });

            if (roomResponse.ok) {
                const roomData = await roomResponse.json();
                if (roomData && roomData.data) {
                    currentRoomInfo = roomData.data;
                    updateGameButtons(); // 방 정보 업데이트 후 버튼 상태 갱신
                    updateUserInfo(); // 헤더 업데이트
                }
            }
        } catch (error) {
            console.error('방 정보 조회 실패:', error);
        }

        // UI 즉시 업데이트
        updateUserInfo();
        await loadRooms();

    } catch (error) {
        alert(error.message);
        currentRoom = null; // 실패 시 현재 방 정보 초기화
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


    currentRoomSubscription = stompClient.subscribe(destination, (message) => {
        console.log("RAW MESSAGE RECEIVED:", JSON.parse(message.body)); // 모든 수신 메시지 확인용 로그
        const chatMessage = JSON.parse(message.body);

        // 개인 메시지는 이제 별도의 토픽에서 처리되므로 여기서는 제외
        if (chatMessage.type === 'PRIVATE_MESSAGE') {
            console.log('개인 메시지는 별도 토픽에서 처리됨:', chatMessage);
            return;
        }

        // ❗ 수정: 구조화된 메시지 타입별 처리
        switch (chatMessage.type) {
            case 'ROOM_CREATED':
                addMessage(chatMessage, 'system')
                console.log("createdRoom 메서드로 메세지 전달")
                break;
            case 'USER_JOINED':
                // ✅ 수정: 방 정보 업데이트 추가
                if (chatMessage.data && chatMessage.data.room) {
                    currentRoomInfo = chatMessage.data.room;
                    updateGameButtons(); // 버튼 상태 즉시 업데이트
                }
                addMessage(chatMessage, 'system');
                break;
            case 'USER_LEFT':
                // 서버가 보내준 방 전체 데이터로 로컬 상태를 덮어쓴다
                if (chatMessage.data && chatMessage.data.room) {
                    currentRoomInfo = chatMessage.data.room;
                }

                // 새로운 데이터로 화면을 다시 그린다
                updateUserInfo();
                updateGameButtons();
                clearChatMessages();

                stompClient()

                // 화면에 보여줄 시스템 메시지를 추가한다
                addMessage(chatMessage, 'system');
                break;

            case 'SYSTEM':
                addMessage(chatMessage, 'system');
                break;

            case 'CHAT':
                const messageType = chatMessage.senderId === currentUser.userLoginId ? 'self' : 'other';
                addMessage(chatMessage, messageType);
                break;

            case 'GAME_START':
                // 게임 시작 상태 업데이트
                if (!chatMessage.game) {
                    console.error('GAME_START 메시지에 game 객체가 없습니다.');
                    return;
                }
                isGameStarted = true;
                currentGameId = chatMessage.game.gameId;
                currentGame = chatMessage.game;

                addMessage({ senderId: 'SYSTEM', content: '게임이 시작되었습니다.' }, 'system');
                // 게임 UI 업데이트
                updateGameUI(currentGame);

                // 버튼 상태 업데이트
                updateGameButtons();

                // 타이머 UI를 화면에 표시
                const gameTimerElement = document.getElementById('gameTimer');
                if (gameTimerElement) {
                    gameTimerElement.style.display = 'block';
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

                    // 통합된 시스템 메시지가 있으면 표시
                    if (chatMessage.systemMessage) {
                        addMessage({ senderId: 'SYSTEM', content: chatMessage.systemMessage }, 'system');
                    }
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


            case 'GAME_ENDED':
                // 게임 종료 메시지 처리
                const winnerTeam = chatMessage.winner === 'MAFIA' ? '마피아 팀' : '시민 팀';
                const gameEndMessage = {
                    type: 'SYSTEM',
                    senderId: 'SYSTEM',
                    content: `🎉 게임 종료! ${winnerTeam}의 승리입니다!`,
                    timestamp: new Date().toISOString()
                };
                addMessage(gameEndMessage, 'system');

                // 게임 UI 숨기기
                hideAllGameUI();

                // 게임 종료 상태로 설정
                isGameStarted = false;
                currentGame = null;
                currentGameId = null;
                break;

            case 'ROLE_DISTRIBUTION':
                // 역할 분포 공개 메시지 처리
                const roleCounts = chatMessage.rolecounts;
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
                // 페이즈 전환 시 시간 연장 사용 기록 초기화
                timeExtensionUsed = false;

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

                        // 투표 페이즈로 전환 시 시간 연장 기회 초기화 및 버튼 활성화
                        if (chatMessage.gamePhase === 'DAY_VOTING') {
                            timeExtensionUsed = false;
                            // 시간 연장/단축 버튼 활성화
                            const extendBtn = document.getElementById('extendTimeBtn');
                            const reduceBtn = document.getElementById('reduceTimeBtn');
                            if (extendBtn) extendBtn.disabled = false;
                            if (reduceBtn) reduceBtn.disabled = false;
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
    if (messageType === 'system') {
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
    // ❗ 수정: 헤더의 사용자 정보 업데이트 (사이드바 제거됨)
    const headerUserInfo = document.getElementById('headerUserInfo');
    const headerUserName = document.getElementById('headerUserName');
    const headerConnectionStatus = document.getElementById('headerConnectionStatus');
    const headerCurrentRoom = document.getElementById('headerCurrentRoom');
    const headerUserRole = document.getElementById('headerUserRole');

    if (currentUser) {
        // 헤더 사용자 정보 표시
        if (headerUserInfo) headerUserInfo.style.display = 'flex';
        if (headerUserName) headerUserName.textContent = currentUser.nickname;

        // 역할 정보 표시
        if (headerUserRole) {
            if (currentUser.role) {
                headerUserRole.textContent = `[${currentUser.role}]`;
                headerUserRole.style.display = 'inline-block';
                // 역할에 따른 색상 스타일링 (선택 사항)
                if (currentUser.role === 'MAFIA') {
                    headerUserRole.style.color = '#ff4444';
                } else if (currentUser.role === 'DOCTOR') {
                    headerUserRole.style.color = '#44ff44';
                } else if (currentUser.role === 'POLICE') {
                    headerUserRole.style.color = '#4444ff';
                } else {
                    headerUserRole.style.color = '#ffffff';
                }
            } else {
                headerUserRole.style.display = 'none';
            }
        }

        // 현재 방 정보 표시
        if (headerCurrentRoom) {
            if (currentRoom && currentRoomInfo && currentRoomInfo.roomName) {
                headerCurrentRoom.textContent = currentRoomInfo.roomName;
                headerCurrentRoom.style.display = 'inline-block';
            } else if (currentRoom) {
                // 방 정보가 없으면 방 ID 표시
                headerCurrentRoom.textContent = currentRoom;
                headerCurrentRoom.style.display = 'inline-block';
            } else {
                headerCurrentRoom.textContent = '로비';
                headerCurrentRoom.style.display = 'inline-block';
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
    } else {
        // 헤더 사용자 정보 숨김
        if (headerUserInfo) headerUserInfo.style.display = 'none';
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

        // 게임 생성 요청 (이제 생성과 시작이 통합됨)
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

        // 게임 시작 상태 업데이트
        isGameStarted = true;
        currentGameId = createResult.gameId;


    } catch (error) {
        console.error('게임 시작 실패:', error);
        alert('게임 시작에 실패했습니다: ' + error.message);
    }
}



// ❗ 추가: 타이머 표시 업데이트
function updateTimerDisplay(game) {
    const timerLabel = document.getElementById('timerLabel');
    const timerCountdown = document.getElementById('timerCountdown');
    const extendButtons = document.querySelectorAll('.timer-controls button');

    if (!isGameStarted) {
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

        // 시간 연장/단축 버튼 제어 (모든 사용자 가능)
        extendButtons.forEach(button => {
            button.style.display = 'inline-block';
        });

        // 투표 페이즈거나 낮 대화 페이즈일 때만 시간 조절 가능
        const isTimeControllablePhase = game.gamePhase === 'DAY_DISCUSSION' || game.gamePhase === 'DAY_VOTING';
        const canExtend = isTimeControllablePhase && !timeExtensionUsed && remainingTime > 0;

        extendButtons.forEach(button => {
            button.disabled = !canExtend;
        });
    }

    // ❗ 추가: 게임 상태에 따른 UI 업데이트
    currentGame = game;
    updateGameUI(game);
}

// ❗ 수정: 시간 연장/단축
async function updateTime(seconds) {
    if (timeExtensionUsed) {
        alert('이번 페이즈에서는 이미 시간 조절을 사용했습니다.');
        return;
    }
    if (!currentGameId || !currentUser || !currentGame) {
        alert('게임 정보를 찾을 수 없습니다.');
        return;
    }

    // 버튼을 즉시 비활성화하고 사용 플래그를 설정
    timeExtensionUsed = true;
    document.getElementById('extendTimeBtn').disabled = true;
    document.getElementById('reduceTimeBtn').disabled = true;

    try {
        const response = await fetch('/api/game/update-time', {
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
        if (!result.success) {
            alert(result.message || '시간 조절에 실패했습니다.');
            // 실패 시 버튼 상태를 되돌릴 수 있으나, 우선 사용된 것으로 처리
        }
        // 성공 시에는 WebSocket 메시지를 통해 타이머가 갱신됨

    } catch (error) {
        console.error('시간 조절 실패:', error);
        alert('시간 조절에 실패했습니다.');
    }
}

// ❗ 추가: 역할별 개인 메시지 구독 설정 (현재는 subscribeToPrivateMessages에서 통합 처리)
function setupRoleBasedSubscriptions(options) {
    // 개인 메시지는 이미 subscribeToPrivateMessages에서 통합 처리됨
    console.log('역할별 구독 설정 - 개인 메시지는 통합 처리됨');
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
        case 'DAY_FINAL_VOTING':
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
    if (!votingArea || !nightActionArea) return;

    votingArea.style.display = 'block';
    nightActionArea.style.display = 'none';

    const votingOptions = document.getElementById('votingOptions');
    votingOptions.innerHTML = ''; // 이전 옵션 초기화

    // 재투표 후보자가 있는지 확인
    const candidates = (game.tieBreakerCandidates && game.tieBreakerCandidates.length > 0)
        ? game.players.filter(p => game.tieBreakerCandidates.includes(p.playerId))
        : game.players.filter(p => p.isAlive);

    candidates.forEach(player => {
        const option = document.createElement('div');
        option.className = 'voting-option';
        option.textContent = player.playerName;
        option.dataset.playerId = player.playerId;
        option.onclick = () => selectVoteTarget(player.playerId);
        votingOptions.appendChild(option);
    });

    selectedVoteTarget = null;
    updateVoteButtons();
}

// ❗ 추가: 밤 액션 UI 표시
function showNightActionUI(game, currentPlayer) {
    const votingArea = document.getElementById('votingArea');
    const nightActionArea = document.getElementById('nightActionArea');

    if (votingArea) votingArea.style.display = 'none';

    // 시민인 경우 밤 액션 UI를 아예 표시하지 않음
    if (currentPlayer.role === 'CITIZEN') {
        if (nightActionArea) nightActionArea.style.display = 'none';
        return;
    }

    if (nightActionArea) nightActionArea.style.display = 'block';

    // 역할에 따른 액션 설정
    const title = document.getElementById('nightActionTitle');
    const description = document.getElementById('nightActionDescription');
    const options = document.getElementById('nightActionOptions');

    if (title && description && options) {
        switch (currentPlayer.role) {
            case 'MAFIA':
                title.textContent = '마피아 - 암살';
                description.textContent = '밤이 되었습니다. 제거할 플레이어를 선택하세요.';
                break;
            case 'DOCTOR':
                title.textContent = '의사 - 치료';
                description.textContent = '밤이 되었습니다. 마피아의 공격으로부터 보호할 플레이어를 선택하세요. (자신 선택 가능)';
                break;
            case 'POLICE':
                title.textContent = '경찰 - 수사';
                description.textContent = '밤이 되었습니다. 마피아인지 조사할 플레이어를 선택하세요.';
                break;
            default:
                title.textContent = '밤 시간';
                description.textContent = '잠시 기다려주세요...';
                break;
        }

        // 액션 대상 플레이어 목록 생성
        options.innerHTML = '';

        game.players.forEach(player => {
            // 의사는 자기 자신도 치료할 수 있음
            const canSelectSelf = currentPlayer.role === 'DOCTOR';
            const isSelf = player.playerId === currentUser.userLoginId;

            if (player.isAlive && (canSelectSelf || !isSelf)) {
                const option = document.createElement('div');
                option.className = 'night-action-option';
                option.textContent = player.playerName + (isSelf ? ' (나)' : '');
                option.dataset.playerId = player.playerId;
                option.onclick = () => selectNightActionTarget(player.playerId);
                options.appendChild(option);
            }
        });
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
        const votedPlayer = game.players.find(p => p.playerId === game.votedPlayerId);
        const votedPlayerName = votedPlayer ? votedPlayer.playerName : '알 수 없음';
        votingDescription.textContent = `최종 투표: ${votedPlayerName}님에 대한 찬성 또는 반대를 선택하세요`;
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

            // 버튼 비활성화 하지 않음 (재투표 가능)
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

            // 버튼 비활성화 하지 않음 (재투표 가능)
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

    // 투표 버튼 상태 업데이트 (제거됨)
    // updateVoteButtons();

    // ❗ 변경: 선택 즉시 투표 제출
    submitVote();
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

    // 액션 버튼 활성화 (제거됨)
    // const submitBtn = document.getElementById('submitNightActionBtn');
    // if (submitBtn) {
    //     submitBtn.disabled = false;
    // }

    // ❗ 변경: 선택 즉시 액션 제출
    submitNightAction();
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

    const votePayload = {
        gameId: currentGameId,
        voterId: currentUser.userLoginId,
        targetId: selectedVoteTarget
    };

    stompClient.send("/app/game.vote", {}, JSON.stringify(votePayload));
    // alert('투표를 완료했습니다.'); // 제거

    // 투표 후 UI 비활성화 하지 않음 (재투표 가능)
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

    const nightActionPayload = {
        gameId: currentGameId,
        actorId: currentUser.userLoginId,
        targetId: selectedNightActionTarget
    };

    stompClient.send("/app/game.nightAction", {}, JSON.stringify(nightActionPayload));
    // alert('액션을 완료했습니다.'); // 제거

    // 액션 후 UI 비활성화 하지 않음 (재선택 가능)
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
            // ✅ 수정: participants 배열에서 실제 참가자 수 계산
            const participantCount = currentRoomInfo.participants ? currentRoomInfo.participants.length : 0;
            const canStartGame = participantCount >= 4;

            console.log('게임 시작 버튼 상태 업데이트:', {
                currentRoom,
                isHost,
                participantCount,
                canStartGame,
                currentRoomInfo
            });

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

    // 현재 방과 게임 시작전 나가기 버튼 표시
    if (leaveRoomBtn) {
        if (currentRoom && !isGameStarted) {
            leaveRoomBtn.style.display = 'inline-block';
        } else {
            leaveRoomBtn.style.display = 'none';
            if (startGameBtn) startGameBtn.style.display = 'none';
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
    const minWaitTime = 5000; // 5초

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

        // ❗ 추가: 버튼 상태 업데이트 (loadRooms에서 이미 호출되지만 확실히 하기 위해)
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

window.onload = async function () {
    const savedToken = localStorage.getItem('jwtToken');
    const savedUser = localStorage.getItem('currentUser');
    if (savedToken && savedUser) {
        try {
            jwtToken = savedToken;
            currentUser = JSON.parse(savedUser);
            document.getElementById('loginForm').classList.add('hidden');
            document.getElementById('registerForm').classList.add('hidden');
            document.getElementById('gameScreen').classList.remove('hidden');

            // WebSocket 연결
            try {
                await connectWebSocket();
            } catch (error) {
                console.error('WebSocket 연결 실패:', error);
            }

            loadRooms();
            updateUserInfo();

            // ❗ 추가: 초기 로드 시 버튼 상태 업데이트
            updateGameButtons();

            // ❗ 추가: 사용자 세션 복구
            await restoreUserSession();
        } catch (e) {
            console.error("Failed to parse user data from localStorage", e);
            localStorage.clear();
        }
    }
}

// ❗ 추가: 방 정보 UI 업데이트 함수
function updateRoomUI() {
    if (currentRoom && currentRoomInfo) {
        // 방 정보 표시
        const roomInfoElement = document.getElementById('roomInfo');
        if (roomInfoElement) {
            roomInfoElement.innerHTML = `
                <h3>${currentRoomInfo.roomName}</h3>
                <p>방장: ${currentRoomInfo.hostName}</p>
                <p>참가자: ${currentRoomInfo.participants ? currentRoomInfo.participants.length : 0}/${currentRoomInfo.maxPlayers}</p>
            `;
        }

        // 참가자 목록 업데이트
        updateParticipantsList();

        // 게임 버튼 상태 업데이트
        updateGameButtons();
    }
}

// ❗ 추가: 개인 메시지 테스트 함수 (디버깅용)
function testPrivateMessage() {
    if (!stompClient || !stompClient.connected) {
        alert('WebSocket이 연결되지 않았습니다.');
        return;
    }

    const recipientId = prompt('메시지를 보낼 사용자 ID를 입력하세요:');
    if (!recipientId) return;

    const testMessage = {
        type: 'PRIVATE_MESSAGE',
        recipient: recipientId,
        content: '테스트 개인 메시지입니다.',
        timestamp: new Date().toISOString()
    };

    console.log('개인 메시지 전송 테스트:', testMessage);
    stompClient.send("/app/chat.sendPrivateMessage", {}, JSON.stringify(testMessage));
}

// ❗ 추가: 사용자 세션 복구 함수
async function restoreUserSession() {
    if (!currentUser || !jwtToken) return;

    try {
        console.log('사용자 세션 복구 시작...');

        // 서버에서 사용자 세션 정보 조회
        const response = await fetch('/api/users/session', {
            method: 'GET',
            headers: { 'Authorization': jwtToken }
        });

        if (response.ok) {
            const sessionData = await response.json();
            console.log('세션 데이터:', sessionData);

            if (sessionData.success && sessionData.data) {
                const { roomId, gameId } = sessionData.data;

                if (roomId) {
                    console.log('방 복구 시작:', roomId);

                    // 방 정보 복구
                    currentRoom = roomId;

                    // WebSocket 연결 확인 및 재연결
                    if (!stompClient || !stompClient.connected) {
                        console.log('WebSocket 재연결 중...');
                        try {
                            await connectWebSocket();
                            console.log('WebSocket 재연결 완료');
                        } catch (error) {
                            console.error('WebSocket 재연결 실패:', error);
                        }
                    }

                    // 방 참가 (WebSocket 연결 포함)
                    await joinRoom(roomId);

                    // 방 정보 조회 및 UI 업데이트
                    try {
                        const roomResponse = await fetch(`/api/chat/rooms/${roomId}`, {
                            method: 'GET',
                            headers: { 'Authorization': jwtToken }
                        });

                        if (roomResponse.ok) {
                            const roomData = await roomResponse.json();
                            if (roomData.success) {
                                currentRoomInfo = roomData.data;
                                updateRoomUI();
                            }
                        }
                    } catch (error) {
                        console.error('방 정보 조회 실패:', error);
                    }

                    if (gameId) {
                        console.log('게임 복구 시작:', gameId);

                        // 게임 정보 복구
                        currentGameId = gameId;
                        isGameStarted = true;

                        // 게임 상태 조회
                        const gameResponse = await fetch(`/api/game/${gameId}`, {
                            method: 'GET',
                            headers: { 'Authorization': jwtToken }
                        });

                        if (gameResponse.ok) {
                            const gameData = await gameResponse.json();
                            if (gameData.success) {
                                currentGame = gameData.data;
                                updateGameUI(currentGame);

                                // 게임 상태에 따른 UI 업데이트
                                if (currentGame.gamePhase === 'NIGHT_ACTION') {
                                    showNightActionUI();
                                } else if (currentGame.gamePhase === 'DAY_VOTING' || currentGame.gamePhase === 'DAY_FINAL_VOTE') {
                                    showVotingUI();
                                }

                                console.log('게임 복구 완료');
                            }
                        }
                    }

                    console.log('세션 복구 완료');
                }
            }
        } else {
            console.log('세션 정보 없음 - 로그인 필요');
        }
    } catch (error) {
        console.error('사용자 세션 복구 실패:', error);
    }
}

// --- 개발자 전용 함수 ---
async function devQuickStart() {
    if (!currentUser) {
        alert('로그인이 필요합니다.');
        return;
    }

    try {
        const response = await fetch('/dev/quick-start', {
            method: 'POST',
            headers: {
                'Authorization': jwtToken,
                'Content-Type': 'application/json'
            }
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error('Quick Start failed: ' + errorText);
        }

        const result = await response.json();
        console.log('Quick Start Success:', result);

        // 방 입장 처리
        await joinRoom(result.roomId);

    } catch (error) {
        console.error('Quick Start Error:', error);
        alert('Quick Start 실패: ' + error.message);
    }
}