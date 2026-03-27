# 마피아 게임 (Mafia Game)

사용자별로 회원을 구분하고 채팅룸에서 마피아 게임을 즐길 수 있는 웹 애플리케이션입니다.

## 🎮 게임 특징

- **실시간 채팅**: WebSocket + STOMP 기반 멀티룸 실시간 채팅
- **역할 기반 게임**: 마피아/의사/경찰/시민 역할과 단계별 진행
- **게임 상태 복구**: Redis 기반 게임 상태 저장 및 재접속 복구 지원
- **분산 타이머 처리**: Redis ZSET + Worker 기반 페이즈 타이머 처리
- **AI 추천 문구**: Gemini API 기반 역할/페이즈별 채팅 추천 제공
- **보안/인증**: JWT + OAuth2, BCrypt 비밀번호 해싱
- **동시성 제어**: Redisson 분산 락으로 입장/퇴장 정합성 보장

## 🚀 기술 스택

- **Backend**: Spring Boot 3.5.4, Java 21 (빌드 기준)
- **Database**: MySQL (기본), H2 (테스트용 선택)
- **Cache/State**: Redis, Redisson
- **WebSocket**: Spring WebSocket, STOMP, SockJS
- **Security**: Spring Security, JWT, OAuth2
- **AI**: Gemini API (채팅 추천)
- **Frontend**: HTML5, CSS3, JavaScript
- **API Docs**: Springdoc OpenAPI (Swagger UI)
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
- Java 21 이상
- MySQL, Redis
- Gradle (프로젝트에 포함됨)

### 1-1. 환경 설정
- `src/main/resources/application-example.properties`를 참고해 `application.properties` 생성
- DB/Redis/JWT 기본 값은 로컬 개발용이며, 실제 값은 환경 변수로 주입 권장
- 권장 환경 변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `JWT_SECRET`, `JWT_ACCESS_EXPIRATION`, `JWT_REFRESH_EXPIRATION`, `GEMINI_API_KEY`
- OAuth2 사용 시 환경 변수 설정: `GOOGLE_CLIENT_ID/SECRET`, `GITHUB_CLIENT_ID/SECRET`, `NAVER_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`
- AI 추천 사용 시 `gemini.api-key` 설정

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

### 사용자/인증
- `POST /api/users/register` - 회원가입
- `POST /api/users/login` - 로그인
- `GET /api/users/me` - 현재 사용자 정보
- `GET /api/users/{userId}` - 사용자 정보 조회
- `PUT /api/users/password` - 비밀번호 변경
- `GET /api/users/session` - 사용자 세션 조회
- `GET /api/users/ranking` - Top 10 랭킹
- `POST /api/auth/refresh` - Access Token 재발급
- `POST /api/auth/logout` - 로그아웃

### 채팅룸
- `POST /api/chat/rooms` - 새 방 생성
- `GET /api/chat/rooms` - 방 목록 조회
- `GET /api/chat/rooms/{roomId}` - 방 정보 조회
- `GET /api/chat/rooms/search?keyword=...` - 방 검색
- `POST /api/chat/rooms/{roomId}/join` - 방 입장

### 게임
- `POST /api/games/create` - 게임 생성
- `GET /api/games/{gameId}/status` - 게임 상태 조회
- `GET /api/games/state/{roomId}` - 방 기준 게임 상태 복구
- `GET /api/games/my-game` - 내 게임 조회
- `POST /api/games/update-time` - 게임 시간 조절
- `GET /api/games/suggestions?role=...&phase=...&gameId=...` - 채팅 추천 조회

### WebSocket 메시지
- `/app/chat.sendMessage` - 채팅 메시지 전송
- `/app/chat.sendPrivateMessage` - 개인 메시지 전송
- `/app/room.join` - 방 입장
- `/app/room.leave` - 방 퇴장
- `/app/game.vote` - 투표
- `/app/game.finalVote` - 최종 투표
- `/app/game.nightAction` - 밤 행동

## 📚 API 문서

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## 🗄️ 데이터베이스

### 기본 DB (MySQL)
- URL: `DB_URL` (기본값: `jdbc:mysql://localhost:3306/mafiaGame`)
- Username: `DB_USERNAME` (기본값: `root`)
- Password: `DB_PASSWORD` (기본값: 빈 값)

### H2 (테스트용 선택)
`application.properties`에서 H2 설정 주석을 해제하면 사용 가능

## 🧠 Redis 사용처

- 채팅방/게임 상태 캐시
- 채팅 로그 저장 (AI 추천 입력)
- Refresh Token 저장

## 📁 프로젝트 구조

```
src/main/java/com/example/mafiagame/
├── config/                 # 설정 클래스
│   └── WebSocketConfig.java
├── global/                 # 공통 설정/보안/JWT/OAuth/Redis
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
- 게임 상태 복구 지원

### 사용자 관리
- 회원가입/로그인
- 사용자 정보 관리
- 방 참가/퇴장 관리

### AI 추천 문구
- 최근 채팅 로그 기반 추천 생성
- 역할/페이즈별 문구 제공

## 🔒 보안 구성

- 비밀번호 BCrypt 해싱
- JWT Access/Refresh Token (Refresh Token은 Redis 저장)
- OAuth2 로그인 (Google/GitHub/Naver/Kakao)

프로덕션 환경에서는 HTTPS, 보안 키 관리, CORS/Rate Limit 정책을 추가로 고려해야 합니다.

## 에러 사항과 해결 방법

### WebSocket 및 인증 관련
- **StompHandler principal 유실 문제**: 인증한 사용자 정보가 다른 스레드에서 작동하는 ChatController로 넘어가는 과정에서 principal 유실 → StompHandler가 인증에 성공한 직후, websocket 세션 공유 공간을 이용하여 principal 정보를 가져오도록 해결
- **WebSocket 연결 타이밍 문제**: 로그인 시 websocket을 바로 연결하면 리소스를 입장 전에 잡아먹어 대규모에 부적합하지만, 사용자가 방에 입장할 때 지연시간이 없고 로비에서 친구의 접속 상태 알림이나 전체 공지사항 등 추후 기능에 용이

### 데이터 관리 및 동기화
- **로컬 상태 관리 취약점**: sendMessage.senderId == "SYSTEM" && message.content.includes("입장") || message.content.includes("퇴장") 일 때 local Count를 수정하여 형식 변경 취약, 데이터 표현 분리 부족 → 서버가 신뢰할 수 있는 단일 데이터 소스(Single Source of Truth) 역할을 하도록 아키텍처를 개선, 사용자가 입장하거나 퇴장할 때 명확한 타입(JOIN, LEAVE)과 함께 방의 최신 참가자 목록 전체를 데이터 페이로드에 담아 전송하도록 해결
- **ConcurrentHashMap 연결 끊김 문제**: ConcurrentHashMap을 통해 WebSocket과의 연결 데이터를 관리하기 때문에 새로고침 시 WebSocket과의 연결이 끊어짐 → Redis를 이용한 WebSocket의 연결 데이터를 관리하게 되면 새로고침을 하더라도 Redis서버에 데이터가 저장되어 지속적인 서비스 연결 가능하여 해결

### 게임 로직 및 성능
- **방장 권한 이전 문제**: 채팅방의 방장이 방 나가기 실행 시 방장 권한이 아무에게도 넘어가지 않아 게임실행이 불가 → 해당 채팅방에 참가자 리스트 순서대로 방장 권한 전가하도록 해결
- **게임 타이머 스레드 문제**: 모든 게임에 하나의 gameTimer를 하나씩 두어 여러개의 game이 시작 시 game 수 만큼의 gameTimer가 생성되고 감당할 수 없는 thread를 사용하게 됨 → Redis ZSET 대기열 + Worker polling 구조로 전환하여 JVM 메모리 타이머 의존성을 제거
- **타이머 정합성 문제**: 시간 연장/단축 시 이전 타이머가 늦게 실행되거나, 워커 장애 시 타이머가 유실될 수 있음 → `timerToken` 기반 stale timer 검증과 processing lease 재큐잉으로 보완
- **JSON 직렬화 순환 참조 문제**: 게임 시작 시 MessageConversionException 발생 (Document nesting depth exceeds the maximum allowed) → Game과 GamePlayer 간의 양방향 참조로 인한 순환 참조 문제였음. Jackson이 JSON 직렬화 시 무한 루프에 빠져서 발생. @JsonManagedReference와 @JsonBackReference 어노테이션을 사용하여 순환 참조를 방지하고 JSON 직렬화 시 깊이 제한을 초과하지 않도록 해결

## 🚧 향후 개선 계획

- [ ] 더 많은 역할 추가 및 밸런싱
- [ ] 모바일 반응형 UI 개선
- [ ] 게임 통계 상세/리플레이 UI
- [ ] 관측성 대시보드(메트릭/로그) 정비
- [ ] 음성 채팅 기능

## 📞 문의 및 지원

프로젝트에 대한 문의사항이나 버그 리포트는 이슈로 등록해 주세요.

## 📄 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다.
