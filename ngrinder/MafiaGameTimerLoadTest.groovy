import static net.grinder.script.Grinder.grinder

import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import HTTPClient.HTTPConnection
import HTTPClient.HTTPResponse
import HTTPClient.NVPair

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * ──────────────────────────────────────────────────────────────
 *  마피아 게임 — Redis ZSET 타이머 부하 테스트 (nGrinder)
 * ──────────────────────────────────────────────────────────────
 *
 *  <p>목적:
 *  <ul>
 *    <li>Redis ZSET + Worker polling 기반 타이머가 다수의 동시 게임에서
 *        안정적으로 페이즈를 전환하는지 검증</li>
 *    <li>Worker의 claimDueTimers / ZRANGEBYSCORE 처리량과
 *        레이턴시 측정</li>
 *  </ul>
 *
 *  <p>시나리오 (vuser 1개 = 게임 1판):
 *  <ol>
 *    <li>방장(host) 1명 + 참가자(guest) 3명 회원가입 & 로그인</li>
 *    <li>방장이 채팅방 생성</li>
 *    <li>참가자 3명이 채팅방 입장 (REST API)</li>
 *    <li>방장이 게임 생성 → Redis ZSET에 첫 타이머 등록</li>
 *    <li>게임 상태를 폴링하며 Worker가 타이머를 처리하여
 *        페이즈가 자동 전환되는지 확인</li>
 *    <li>게임 종료(ENDED) 또는 타임아웃까지 대기</li>
 *  </ol>
 *
 *  <p>주요 측정 지표:
 *  <ul>
 *    <li>게임 생성 ~ 첫 페이즈 전환 레이턴시</li>
 *    <li>전체 게임 사이클(5페이즈) 소요 시간</li>
 *    <li>Worker 폴링 실패율 (페이즈가 전환되지 않는 경우)</li>
 *  </ul>
 */
@RunWith(GrinderRunner)
class MafiaGameTimerLoadTest {

    // ════════════════════════════════════════════════════════
    //  설정값
    // ════════════════════════════════════════════════════════

    /** 대상 서버 호스트 */
    static final String HOST = "localhost"

    /** 대상 서버 포트 */
    static final int PORT = 8080

    /** 게임 종료 대기 최대 시간 (ms) */
    static final long GAME_TIMEOUT_MS = 5 * 60 * 1000L

    /** 게임 상태 폴링 간격 (ms) */
    static final long POLL_INTERVAL_MS = 2_000L

    /** 한 방에 필요한 플레이어 수 (최소 4명) */
    static final int PLAYERS_PER_ROOM = 4

    // ════════════════════════════════════════════════════════
    //  GTest — nGrinder 측정 단위
    // ════════════════════════════════════════════════════════

    static GTest testRegister
    static GTest testLogin
    static GTest testCreateRoom
    static GTest testJoinRoom
    static GTest testCreateGame
    static GTest testPollStatus
    static GTest testAdvancePhase

    // ════════════════════════════════════════════════════════
    //  인스턴스 필드
    // ════════════════════════════════════════════════════════

    HTTPConnection connection

    def jsonSlurper = new JsonSlurper()

    /** vuser 고유 식별자 (thread × run 조합) */
    String vuserPrefix
    int threadId
    int runCount = 0

    /** 플레이어별 JWT 토큰 */
    String[] tokens = new String[PLAYERS_PER_ROOM]

    /** 플레이어별 loginId */
    String[] userIds = new String[PLAYERS_PER_ROOM]

    String roomId
    String gameId

    // ════════════════════════════════════════════════════════
    //  nGrinder 라이프사이클 콜백
    // ════════════════════════════════════════════════════════

    @BeforeProcess
    static void beforeProcess() {
        testRegister     = new GTest(1, "01_Register")
        testLogin        = new GTest(2, "02_Login")
        testCreateRoom   = new GTest(3, "03_CreateRoom")
        testJoinRoom     = new GTest(4, "04_JoinRoom")
        testCreateGame   = new GTest(5, "05_CreateGame")
        testPollStatus   = new GTest(6, "06_PollGameStatus")
        testAdvancePhase = new GTest(7, "07_ForceAdvancePhase")

        grinder.logger.info("[beforeProcess] GTest 인스턴스 생성 완료")
    }

    @BeforeThread
    void beforeThread() {
        threadId = grinder.threadNumber

        // nGrinder HTTPClient 기반 커넥션 생성
        connection = new HTTPConnection(HOST, PORT)
        connection.setTimeout(30_000)

        // GTest.record — 개별 메서드의 TPS / 응답시간 측정
        testRegister.record(this, "doRegister")
        testLogin.record(this, "doLogin")
        testCreateRoom.record(this, "doCreateRoom")
        testJoinRoom.record(this, "doJoinRoom")
        testCreateGame.record(this, "doCreateGame")
        testPollStatus.record(this, "doPollStatus")
        testAdvancePhase.record(this, "doAdvancePhase")

        grinder.logger.info("[beforeThread] threadId={}", threadId)
    }

    @Before
    void before() {
        runCount++
        vuserPrefix = "lt_t${threadId}_r${runCount}_"

        tokens = new String[PLAYERS_PER_ROOM]
        userIds = new String[PLAYERS_PER_ROOM]
        roomId = null
        gameId = null

        grinder.logger.info("[before] vuserPrefix={}", vuserPrefix)
    }

    // ════════════════════════════════════════════════════════
    //  메인 테스트 시나리오
    // ════════════════════════════════════════════════════════

    @Test
    void testFullGameCycleWithRedisTimer() {

        // ── STEP 1: 회원가입 & 로그인 ──
        for (int i = 0; i < PLAYERS_PER_ROOM; i++) {
            userIds[i] = vuserPrefix + "user" + i
            doRegister(i)
            doLogin(i)
        }
        grinder.logger.info("[STEP 1] 회원가입 & 로그인 완료 ({}명)", PLAYERS_PER_ROOM)

        // ── STEP 2: 채팅방 생성 (방장 = user0) ──
        doCreateRoom()
        assert roomId != null : "채팅방 생성 실패"
        grinder.logger.info("[STEP 2] 채팅방 생성 완료: roomId={}", roomId)

        // ── STEP 3: 참가자 입장 ──
        for (int i = 1; i < PLAYERS_PER_ROOM; i++) {
            doJoinRoom(i)
        }
        grinder.logger.info("[STEP 3] 참가자 입장 완료 ({}명)", PLAYERS_PER_ROOM - 1)

        // ── STEP 4: 게임 생성 → Redis ZSET에 첫 타이머 등록 ──
        doCreateGame()
        assert gameId != null : "게임 생성 실패"
        grinder.logger.info("[STEP 4] 게임 생성 완료: gameId={}", gameId)

        // ── STEP 5: 타이머 기반 페이즈 자동 전환 관찰 ──
        observeTimerDrivenPhaseTransitions()
    }

    // ════════════════════════════════════════════════════════
    //  개별 API 호출 메서드 (GTest.record 대상)
    // ════════════════════════════════════════════════════════

    /**
     * 회원가입 API 호출.
     *
     * @param playerIndex 플레이어 인덱스 (0 ~ PLAYERS_PER_ROOM-1)
     */
    void doRegister(int playerIndex) {
        String loginId = userIds[playerIndex]
        String password = "testpassword" + playerIndex  // 12자 (min=10)

        byte[] body = JsonOutput.toJson([
            nickname         : loginId,
            userLoginId      : loginId,
            userLoginPassword: password
        ]).getBytes("UTF-8")

        NVPair[] headers = [
            new NVPair("Content-Type", "application/json; charset=UTF-8")
        ]

        HTTPResponse response = connection.Post(
            "/api/users/register", body, headers)

        if (response.statusCode == 201) {
            grinder.logger.info("[Register] 성공: {}", loginId)
        } else if (response.statusCode == 409 || response.statusCode == 400) {
            grinder.logger.info("[Register] 이미 등록됨(skip): {}", loginId)
        } else {
            grinder.logger.warn("[Register] 실패: status={}, body={}",
                response.statusCode, response.getText())
        }
    }

    /** record 용 오버로드 (기본 인덱스 0). */
    void doRegister() { doRegister(0) }

    /**
     * 로그인 API 호출 → JWT 토큰 저장.
     *
     * @param playerIndex 플레이어 인덱스
     */
    void doLogin(int playerIndex) {
        String loginId = userIds[playerIndex]
        String password = "testpassword" + playerIndex

        byte[] body = JsonOutput.toJson([
            userLoginId      : loginId,
            userLoginPassword: password
        ]).getBytes("UTF-8")

        NVPair[] headers = [
            new NVPair("Content-Type", "application/json; charset=UTF-8")
        ]

        HTTPResponse response = connection.Post(
            "/api/users/login", body, headers)

        assert response.statusCode == 200 :
            "로그인 실패: ${loginId}, status=${response.statusCode}"

        def json = jsonSlurper.parseText(response.getText())
        tokens[playerIndex] = json.data?.token

        assert tokens[playerIndex] != null :
            "JWT 토큰이 null: ${loginId}"

        grinder.logger.info("[Login] 성공: {} → token={}...",
            loginId, tokens[playerIndex].take(20))
    }

    /** record 용 오버로드 (기본 인덱스 0). */
    void doLogin() { doLogin(0) }

    /**
     * 채팅방 생성 API 호출 (방장 = user0).
     */
    void doCreateRoom() {
        byte[] body = JsonOutput.toJson([
            roomName: "LoadTest_${vuserPrefix}"
        ]).getBytes("UTF-8")

        NVPair[] headers = authHeaders(tokens[0])

        HTTPResponse response = connection.Post(
            "/api/chat/rooms", body, headers)

        assert response.statusCode == 201 :
            "채팅방 생성 실패: status=${response.statusCode}, body=${response.getText()}"

        def json = jsonSlurper.parseText(response.getText())
        roomId = json.roomId

        grinder.logger.info("[CreateRoom] roomId={}", roomId)
    }

    /**
     * 참가자 채팅방 입장 API 호출.
     *
     * @param playerIndex 참가자 인덱스 (1 ~ PLAYERS_PER_ROOM-1)
     */
    void doJoinRoom(int playerIndex) {
        byte[] body = JsonOutput.toJson([
            roomId: roomId,
            userId: userIds[playerIndex]
        ]).getBytes("UTF-8")

        NVPair[] headers = authHeaders(tokens[playerIndex])

        HTTPResponse response = connection.Post(
            "/api/chat/rooms/${roomId}/join", body, headers)

        assert response.statusCode == 200 || response.statusCode == 204 :
            "채팅방 입장 실패: player=${userIds[playerIndex]}, status=${response.statusCode}"

        grinder.logger.info("[JoinRoom] {} → roomId={}", userIds[playerIndex], roomId)
    }

    /** record 용 오버로드. */
    void doJoinRoom() { doJoinRoom(1) }

    /**
     * 게임 생성 API 호출.
     *
     * <p>성공 시 서버 측에서:
     * <ol>
     *   <li>GameState를 Redis에 저장</li>
     *   <li>역할 배정</li>
     *   <li>Redis ZSET {@code game:timer:waiting}에 첫 타이머(NIGHT_ACTION, 15초) 등록</li>
     * </ol>
     */
    void doCreateGame() {
        NVPair[] headers = authHeaders(tokens[0])

        HTTPResponse response = connection.Post(
            "/api/games/create", "".getBytes("UTF-8"), headers)

        assert response.statusCode == 200 || response.statusCode == 201 :
            "게임 생성 실패: status=${response.statusCode}, body=${response.getText()}"

        def json = jsonSlurper.parseText(response.getText())
        gameId = json.gameId

        grinder.logger.info("[CreateGame] gameId={}", gameId)
    }

    /**
     * 게임 상태 폴링.
     *
     * <p>Worker가 Redis ZSET에서 만료된 타이머를 {@code claimDueTimers()}로 처리하여
     * {@code advancePhase()}를 호출하면, 여기서 변경된 페이즈가 관찰된다.
     *
     * @return 현재 게임 상태 문자열 (IN_PROGRESS / ENDED 등)
     */
    String doPollStatus() {
        NVPair[] headers = authHeaders(tokens[0])

        HTTPResponse response = connection.Get(
            "/api/games/${gameId}/status", null, headers)

        assert response.statusCode == 200 :
            "게임 상태 조회 실패: status=${response.statusCode}"

        def json = jsonSlurper.parseText(response.getText())
        def game = json.game

        String phase = game?.gamePhase ?: game?.status ?: "UNKNOWN"
        int currentPhase = game?.currentPhase ?: 0
        String status = game?.status ?: "UNKNOWN"

        grinder.logger.info(
            "[PollStatus] gameId={}, status={}, phase={}, currentPhase={}",
            gameId, status, phase, currentPhase)

        return status
    }

    /**
     * 강제 페이즈 진행 API (테스트 가속용).
     *
     * <p>Worker의 500ms 폴링을 기다리지 않고 즉시 다음 페이즈로 진행시킨다.
     * 대량의 게임이 동시에 돌아갈 때 Worker 병목을 강제로 유발하는 데 사용할 수 있다.
     */
    void doAdvancePhase() {
        NVPair[] headers = authHeaders(tokens[0])

        HTTPResponse response = connection.Post(
            "/api/games/test/${gameId}/advance", "".getBytes("UTF-8"), headers)

        grinder.logger.info("[AdvancePhase] gameId={}, status={}", gameId, response.statusCode)
    }

    // ════════════════════════════════════════════════════════
    //  타이머 기반 페이즈 전환 관찰
    // ════════════════════════════════════════════════════════

    /**
     * Redis ZSET Worker가 타이머를 처리하여 페이즈가 자동 전환되는
     * 과정을 폴링으로 관찰한다.
     *
     * <p>페이즈별 듀레이션 (서버 설정 기준):
     * <table>
     *   <tr><td>NIGHT_ACTION</td>    <td>30초 (첫 시작은 15초)</td></tr>
     *   <tr><td>DAY_DISCUSSION</td>  <td>60초</td></tr>
     *   <tr><td>DAY_VOTING</td>      <td>30초</td></tr>
     *   <tr><td>DAY_FINAL_DEFENSE</td><td>30초 (투표 결과에 따라)</td></tr>
     *   <tr><td>DAY_FINAL_VOTING</td> <td>30초 (투표 결과에 따라)</td></tr>
     * </table>
     *
     * <p>Worker가 {@code game:timer:waiting} ZSET에서 
     * {@code ZRANGEBYSCORE(-inf, now)} 로 만료 타이머를 조회하고,
     * {@code game:timer:processing}으로 이동 후 
     * {@code GameService.advancePhase()}를 호출한다.
     */
    void observeTimerDrivenPhaseTransitions() {
        long startTime = System.currentTimeMillis()
        String lastPhase = ""
        int phaseChangeCount = 0

        grinder.logger.info("═══════════════════════════════════════")
        grinder.logger.info("  Redis ZSET 타이머 관찰 시작")
        grinder.logger.info("  Worker poll: 500ms | 타임아웃: {}ms", GAME_TIMEOUT_MS)
        grinder.logger.info("═══════════════════════════════════════")

        while (System.currentTimeMillis() - startTime < GAME_TIMEOUT_MS) {
            String status = doPollStatus()

            // 게임 종료 확인
            if ("ENDED".equalsIgnoreCase(status)) {
                long elapsed = System.currentTimeMillis() - startTime
                grinder.logger.info("═══════════════════════════════════════")
                grinder.logger.info("  게임 종료!")
                grinder.logger.info("  총 소요: {}ms | 페이즈 전환: {}회",
                    elapsed, phaseChangeCount)
                grinder.logger.info("═══════════════════════════════════════")
                return
            }

            // 페이즈 변경 감지 (별도 상세 조회)
            NVPair[] headers = authHeaders(tokens[0])
            HTTPResponse detailResponse = connection.Get(
                "/api/games/${gameId}/status", null, headers)

            if (detailResponse.statusCode == 200) {
                def detail = jsonSlurper.parseText(detailResponse.getText())
                String currentPhase = detail.game?.gamePhase ?: ""

                if (currentPhase && currentPhase != lastPhase) {
                    long elapsed = System.currentTimeMillis() - startTime
                    grinder.logger.info(
                        "  ▶ 페이즈 전환 [+{}ms]: {} → {} (day={})",
                        elapsed, lastPhase ?: "INIT", currentPhase,
                        detail.game?.currentPhase)
                    lastPhase = currentPhase
                    phaseChangeCount++
                }
            }

            Thread.sleep(POLL_INTERVAL_MS)
        }

        // 타임아웃
        long elapsed = System.currentTimeMillis() - startTime
        grinder.logger.warn("═══════════════════════════════════════")
        grinder.logger.warn("  타임아웃! ({}ms)", elapsed)
        grinder.logger.warn("  페이즈 전환 횟수: {}", phaseChangeCount)
        grinder.logger.warn("═══════════════════════════════════════")
    }

    // ════════════════════════════════════════════════════════
    //  헬퍼
    // ════════════════════════════════════════════════════════

    /**
     * JWT 인증 + JSON Content-Type 헤더 배열 생성.
     *
     * @param token JWT 액세스 토큰
     * @return NVPair 헤더 배열
     */
    private NVPair[] authHeaders(String token) {
        return [
            new NVPair("Content-Type", "application/json; charset=UTF-8"),
            new NVPair("Authorization", "Bearer ${token}")
        ] as NVPair[]
    }
}
