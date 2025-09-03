# 마피아 게임 (Mafia Game)

사용자별로 회원을 구분하고 채팅룸에서 마피아 게임을 즐길 수 있는 웹 애플리케이션입니다.

## 🎮 게임 특징

- **실시간 채팅**: WebSocket을 통한 실시간 채팅 기능
- **역할 기반 게임**: 마피아, 의사, 경찰, 시민 등 다양한 역할
- **방 시스템**: 여러 채팅룸에서 동시에 게임 진행 가능
- **사용자 관리**: 회원가입, 로그인, 사용자 정보 관리

## 🚀 기술 스택

- **Backend**: Spring Boot 3.5.4, Java 17
- **Database**: H2 Database (인메모리)
- **WebSocket**: Spring WebSocket, STOMP
- **Frontend**: HTML5, CSS3, JavaScript
- **Build Tool**: Gradle

## 📋 게임 규칙

### 기본 규칙
- 최소 4명 이상의 플레이어가 필요합니다
- 마피아는 밤에 한 명을 선택하여 죽일 수 있습니다
- 의사는 밤에 한 명을 선택하여 살릴 수 있습니다
- 경찰은 밤에 한 명을 조사할 수 있습니다
- 낮에는 모든 플레이어가 토론하고 투표합니다

### 승리 조건
- **시민팀**: 모든 마피아를 제거
- **마피아팀**: 마피아 수가 시민 수 이상이 되었을 때

## 🛠️ 설치 및 실행

### 1. 사전 요구사항
- Java 17 이상
- Gradle (프로젝트에 포함됨)

### 2. 프로젝트 클론
```bash
git clone <repository-url>
cd mafiagame
```

### 3. 애플리케이션 실행
```bash
./gradlew bootRun
```

### 4. 브라우저에서 접속
```
http://localhost:8080
```

## 📱 사용법

### 1. 회원가입/로그인
- 웹페이지에서 회원가입 또는 로그인
- 사용자명과 비밀번호 입력

### 2. 채팅룸 입장
- 기존 방 목록에서 원하는 방 선택
- 또는 "새 방 만들기"로 새로운 방 생성

### 3. 게임 시작
- 방에 4명 이상 모이면 "게임 시작" 버튼 활성화
- 게임 시작 시 각 플레이어에게 역할 자동 할당

### 4. 게임 진행
- 밤/낮 단계별로 진행
- 각 역할에 맞는 행동 수행
- 채팅을 통한 토론 및 투표

## 🔧 API 엔드포인트

### 사용자 관리
- `POST /api/users/register` - 회원가입
- `POST /api/users/login` - 로그인
- `GET /api/users/{userId}` - 사용자 정보 조회
- `PUT /api/users/{userId}/password` - 비밀번호 변경

### 채팅룸 관리
- `POST /api/chat/rooms` - 새 방 생성
- `GET /api/chat/rooms` - 방 목록 조회
- `GET /api/chat/rooms/{roomId}` - 방 정보 조회
- `POST /api/chat/rooms/{roomId}/join` - 방 입장
- `POST /api/chat/rooms/{roomId}/leave` - 방 퇴장
- `POST /api/chat/rooms/{roomId}/start-game` - 게임 시작
- `POST /api/chat/rooms/{roomId}/end-game` - 게임 종료

### WebSocket 메시지
- `/app/chat.sendMessage` - 채팅 메시지 전송
- `/app/room.join` - 방 입장
- `/app/room.leave` - 방 퇴장
- `/app/game.start` - 게임 시작
- `/app/game.vote` - 투표
- `/app/game.nextPhase` - 다음 단계로 진행

## 🗄️ 데이터베이스

### H2 콘솔 접속
```
http://localhost:8080/h2-console
```

### 데이터베이스 설정
- URL: `jdbc:h2:mem:testdb`
- Username: `sa`
- Password: (비어있음)

## 📁 프로젝트 구조

```
src/main/java/com/example/mafiagame/
├── config/                 # 설정 클래스
│   └── WebSocketConfig.java
├── chat/                   # 채팅 관련
│   ├── controller/         # WebSocket 및 REST 컨트롤러
│   ├── domain/            # 도메인 모델
│   ├── dto/               # 데이터 전송 객체
│   └── service/           # 비즈니스 로직
├── game/                   # 게임 관련
│   └── domain/            # 게임 도메인 모델
├── user/                   # 사용자 관련
│   ├── controller/         # 사용자 컨트롤러
│   ├── domain/            # 사용자 도메인 모델
│   ├── repository/        # 데이터 접근 계층
│   └── service/           # 사용자 비즈니스 로직
├── GamePhase.java          # 게임 단계 enum
├── PlayerRole.java         # 플레이어 역할 enum
└── MafiaGameApplication.java # 메인 애플리케이션
```

## 🎯 주요 기능

### 실시간 채팅
- WebSocket을 통한 실시간 메시지 전송
- 방별 채팅 분리
- 시스템 메시지와 사용자 메시지 구분

### 게임 로직
- 자동 역할 할당
- 단계별 게임 진행
- 투표 시스템
- 승리 조건 확인

### 사용자 관리
- 회원가입/로그인
- 사용자 정보 관리
- 방 참가/퇴장 관리

## 🔒 보안 고려사항

현재 버전은 개발용으로 구현되어 있습니다. 프로덕션 환경에서는 다음 사항을 고려해야 합니다:

- 비밀번호 암호화 (BCrypt 등)
- JWT 토큰 기반 인증
- HTTPS 적용
- 입력값 검증 및 XSS 방지

## 에러 사항과 해결 방법
- StompHandler가 인증한 사용자 정보가 다른 스레드에서 작동하는 ChatController로 넘어가는 과정에서 principal 유실 -> StompHandler가 인증에 성공한 직후, 그 사용자 정보를 websocket 세션 공유 공간 이용하여 principal 정보를 가져옴
- 로그인시 websocket을 바로 연결하기, createRoom 당시 websocket 연결 중 로그인시 websocket 연결하게되면 리소스를 입장전에 잡아먹기 때문에 대규모에 부적합, 하지만 사용자가 방에 입장하기위해 지연시간이 없고 로비에서 친구의 접속 상태 알림이나 전체 공지사항등 추후 기능에 용이
- sendMassage.senderId == "SYSTEM" && message.content.includes("입장") || message.content.includes("퇴장") 일떄 local Count를 수정하여 형식 변경 취약, 데이터 표현 분리 x ->  서버가 신뢰할 수 있는 단일 데이터 소스(Single Source of Truth) 역할을 하도록 아키텍처를 개선, 사용자가 입장하거나 퇴장할 때, 단순히 "입장했습니다"와 같은 텍스트만 보내는 것이 아니라, 명확한 타입(JOIN, LEAVE)과 함께 방의 최신 참가자 목록 전체를 데이터(data) 페이로드에 담아 전송 JOIN 또는 LEAVE 타입의 메시지를 수신하면, 함께 전달된 최신 참가자 목록 데이터로 로컬 상태를 완전히 덮어쓰는 방식
- 채팅방의 방장이 방 나가기 실행 시 방장 권한이 아무에게도 넘어가지 않아 게임실행이 불가 -> 해당 채팅방에 참가자 리스트 순서대로  방장 권한 전가

## 🚧 향후 개선 계획

- [ ] 게임 타이머 기능
- [ ] 게임 히스토리 저장
- [ ] 더 많은 역할 추가
- [ ] 모바일 반응형 UI
- [ ] 게임 통계 및 랭킹 시스템
- [ ] 음성 채팅 기능

## 📞 문의 및 지원

프로젝트에 대한 문의사항이나 버그 리포트는 이슈로 등록해 주세요.

## 📄 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다.
