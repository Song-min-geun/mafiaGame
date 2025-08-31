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
