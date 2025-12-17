# nGrinder 마피아 게임 성능 테스트 스크립트

## 개요

이 디렉토리에는 마피아 게임 프로젝트의 `@Scheduled` 기반 중앙 집중식 타이머 성능을 테스트하기 위한 nGrinder 스크립트들이 포함되어 있습니다.

## 테스트 스크립트 구성

### 1. MafiaGameTimerPerformanceTest.groovy
**종합 성능 테스트** - 전체 유저 플로우를 테스트합니다.

- 로그인 (JWT 토큰 발급)
- 채팅방 생성 및 참가
- 게임 생성 및 시작
- 게임 상태 조회

### 2. TimerLoadTest.groovy
**타이머 부하 집중 테스트** - `GameTimerService`의 `@Scheduled(fixedRate = 1000)` 성능을 집중 테스트합니다.

- 다수 게임 동시 생성
- 1초 간격 연속 폴링 (타이머 주기와 동일)
- 응답 시간 통계 측정

### 3. WebSocketConnectionTest.groovy
**WebSocket 연결 테스트** - STOMP 엔드포인트의 연결 성능을 테스트합니다.

- SockJS 핸드셰이크
- 다중 동시 연결 시뮬레이션

## 사전 요구사항

### 1. 서버 실행
```bash
cd /Users/qwert/Desktop/mafiagame
./gradlew bootRun
```

### 2. 더미 사용자 생성 확인
서버 시작 시 `CommandLineRunner`가 자동으로 `dummy1` ~ `dummy10` 사용자를 생성합니다.

더미 사용자 정보:
- 아이디: `dummy1` ~ `dummy10`
- 비밀번호: `password1234!`

### 3. nGrinder 설정

#### nGrinder 컨트롤러에 스크립트 업로드
1. nGrinder 웹 콘솔 접속 (기본: `http://localhost:8880`)
2. **Script** > **Create a new script** 선택
3. 스크립트 유형: **Groovy**
4. 스크립트 내용 붙여넣기 또는 파일 업로드

## nGrinder 테스트 실행 가이드

### 테스트 설정 권장값

| 테스트 유형 | VUser 수 | 프로세스 수 | 스레드 수 | 실행 시간 |
|------------|---------|-----------|---------|---------|
| 기본 성능 테스트 | 10 | 1 | 10 | 5분 |
| 중간 부하 테스트 | 50 | 2 | 25 | 10분 |
| 고부하 테스트 | 100 | 4 | 25 | 15분 |
| 스트레스 테스트 | 200+ | 8 | 25 | 30분 |

### 설정 방법

1. **Performance Test** > **Create Test** 클릭
2. 테스트 이름 입력 (예: "Mafia Game Timer Load Test")
3. **Agent** 설정
   - 사용할 에이전트 수 선택
4. **Script** 설정
   - 업로드한 스크립트 선택
5. **VUser** 설정
   - **Processes**: 프로세스 수
   - **Threads per Process**: 프로세스당 스레드 수
6. **Duration** 설정
   - 테스트 실행 시간 설정
7. **Ramp-up** 설정 (선택)
   - 점진적 부하 증가 설정

## 측정 지표

### 핵심 메트릭

| 메트릭 | 설명 | 목표값 |
|-------|-----|-------|
| TPS | 초당 트랜잭션 수 | 높을수록 좋음 |
| Mean Test Time | 평균 응답 시간 | < 100ms |
| Peak Test Time | 최대 응답 시간 | < 500ms |
| Error Rate | 에러율 | < 1% |

### 타이머 관련 메트릭

- **게임 상태 폴링 응답 시간**: 타이머 업데이트가 제시간에 처리되는지 확인
- **동시 게임 처리량**: N개의 게임이 동시에 진행될 때 처리 가능 여부
- **스케줄러 지연**: 1초 간격 타이머가 실제로 1초에 실행되는지 확인

## 서버 URL 변경

테스트 대상 서버 URL을 변경하려면 각 스크립트의 상단에서 수정:

```groovy
public static String baseUrl = "http://localhost:8080"
```

예시 (원격 서버):
```groovy
public static String baseUrl = "http://your-server.com:8080"
```

## 트러블슈팅

### 1. 로그인 실패
더미 사용자 비밀번호 확인:
```groovy
// UserService.createDummyUsers() 메서드의 기본 비밀번호
userLoginPassword: "password1234!"
```

### 2. 게임 생성 실패
- 최소 4명의 플레이어가 필요합니다.
- 이미 진행 중인 게임이 있으면 실패할 수 있습니다.

### 3. WebSocket 연결 실패
- CORS 설정 확인
- WebSocket 엔드포인트 `/ws` 확인

## 테스트 결과 해석

### 성공 기준

1. **TPS > 100**: 초당 100건 이상의 요청 처리
2. **Mean Test Time < 100ms**: 평균 응답 시간 100ms 미만
3. **Error Rate < 1%**: 에러율 1% 미만
4. **타이머 정확도**: 게임 상태의 `remainingTime`이 매초 정확히 감소

### 병목 지점 분석

| 증상 | 가능한 원인 | 해결 방안 |
|-----|-----------|---------|
| 응답 시간 급증 | 스레드 풀 부족 | 스레드 풀 크기 증가 |
| 에러율 상승 | 동시 접속 한계 | 커넥션 풀 조정 |
| 타이머 지연 | CPU 과부하 | 서버 스케일 조정 |

## 관련 코드

- 타이머 서비스: `GameTimerService.java`
- 게임 서비스: `GameService.java`
- WebSocket 설정: `WebSocketConfig.java`
