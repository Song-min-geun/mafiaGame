import static net.grinder.script.Grinder.grinder
import static org.junit.Assert.*
import static org.hamcrest.Matchers.*
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPPluginControl
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import HTTPClient.HTTPResponse
import HTTPClient.NVPair
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * 마피아 게임 - @Scheduled 중앙 집중식 타이머 성능 테스트
 * 
 * 테스트 시나리오:
 * 1. 다수 사용자 동시 로그인
 * 2. 채팅방 생성 및 참가
 * 3. 게임 생성 및 시작 (다수 게임 동시 진행)
 * 4. 게임 상태 조회 (타이머 부하 확인)
 */
@RunWith(GrinderRunner)
class MafiaGameTimerPerformanceTest {
    
    // 테스트 대상 서버 설정
    public static String baseUrl = "http://localhost:8080"
    
    public static GTest loginTest
    public static GTest createRoomTest
    public static GTest joinRoomTest
    public static GTest createGameTest
    public static GTest gameStatusTest
    public static GTest getAllRoomsTest
    
    public static HTTPRequest request
    public static JsonSlurper jsonSlurper
    
    // 스레드별 변수
    String jwtToken
    String currentUserId
    String currentRoomId
    String currentGameId
    int threadNum
    
    @BeforeProcess
    public static void beforeProcess() {
        // 테스트 설정
        loginTest = new GTest(1, "로그인")
        createRoomTest = new GTest(2, "채팅방 생성")
        joinRoomTest = new GTest(3, "채팅방 참가")
        createGameTest = new GTest(4, "게임 생성")
        gameStatusTest = new GTest(5, "게임 상태 조회")
        getAllRoomsTest = new GTest(6, "전체 방 목록 조회")
        
        request = new HTTPRequest()
        jsonSlurper = new JsonSlurper()
        
        // HTTP 클라이언트 설정
        HTTPPluginControl.getConnectionDefaults().timeout = 30000
        
        grinder.logger.info("테스트 초기화 완료")
    }
    
    @BeforeThread
    public void beforeThread() {
        threadNum = grinder.threadNumber
        
        // 각 테스트에 메트릭 기록
        loginTest.record(this, "testLogin")
        createRoomTest.record(this, "testCreateRoom")
        joinRoomTest.record(this, "testJoinRoom")
        createGameTest.record(this, "testCreateGame")
        gameStatusTest.record(this, "testGameStatus")
        getAllRoomsTest.record(this, "testGetAllRooms")
        
        grinder.statistics.delayReports = true
        grinder.logger.info("스레드 ${threadNum} 초기화 완료")
    }
    
    @Before
    public void before() {
        // 테스트 실행 전 초기화
        currentUserId = "dummy${(threadNum % 10) + 1}"
        grinder.logger.info("테스트 사용자: ${currentUserId}")
    }
    
    /**
     * 1. 로그인 테스트
     * 더미 사용자로 로그인하여 JWT 토큰 획득
     */
    @Test
    public void testLogin() {
        def loginPayload = JsonOutput.toJson([
            userLoginId: currentUserId,
            userLoginPassword: "password1234!"  // 더미 사용자 기본 비밀번호
        ])
        
        def headers = [
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/users/login",
            loginPayload.getBytes("UTF-8"),
            headers as NVPair[]
        )
        
        assertThat("로그인 응답 코드", response.statusCode, is(200))
        
        def result = jsonSlurper.parseText(response.getText())
        assertThat("로그인 성공 여부", result.success, is(true))
        assertNotNull("토큰 존재 여부", result.data?.token)
        
        jwtToken = result.data.token
        grinder.logger.info("로그인 성공: ${currentUserId}, Token: ${jwtToken?.take(30)}...")
    }
    
    /**
     * 2. 전체 방 목록 조회 테스트
     * 타이머 스케줄러가 동작 중인 상황에서 방 목록 조회 성능 확인
     */
    @Test
    public void testGetAllRooms() {
        ensureAuthenticated()
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.GET(
            "${baseUrl}/api/chat/rooms",
            null,
            headers as NVPair[]
        )
        
        assertThat("방 목록 조회 응답 코드", response.statusCode, is(200))
        
        def rooms = jsonSlurper.parseText(response.getText())
        grinder.logger.info("방 목록 조회 성공: ${rooms?.size() ?: 0}개 방")
    }
    
    /**
     * 3. 채팅방 생성 테스트
     */
    @Test
    public void testCreateRoom() {
        ensureAuthenticated()
        
        def roomPayload = JsonOutput.toJson([
            roomName: "테스트방_${threadNum}_${System.currentTimeMillis()}",
            userId: currentUserId
        ])
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/chat/rooms",
            roomPayload.getBytes("UTF-8"),
            headers as NVPair[]
        )
        
        assertThat("방 생성 응답 코드", response.statusCode, is(200))
        
        def room = jsonSlurper.parseText(response.getText())
        assertNotNull("방 ID 존재 여부", room?.roomId)
        
        currentRoomId = room.roomId
        grinder.logger.info("채팅방 생성 성공: ${currentRoomId}")
    }
    
    /**
     * 4. 채팅방 참가 테스트 (특정 방 조회)
     */
    @Test
    public void testJoinRoom() {
        ensureAuthenticated()
        
        // 방이 없으면 먼저 생성
        if (!currentRoomId) {
            testCreateRoom()
        }
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.GET(
            "${baseUrl}/api/chat/rooms/${currentRoomId}",
            null,
            headers as NVPair[]
        )
        
        assertThat("방 조회 응답 코드", response.statusCode, is(200))
        
        def result = jsonSlurper.parseText(response.getText())
        assertThat("방 조회 성공 여부", result.success, is(true))
        
        grinder.logger.info("채팅방 참가 확인: ${currentRoomId}")
    }
    
    /**
     * 5. 게임 생성 테스트
     * 다수 게임 동시 생성 시 @Scheduled 타이머 부하 테스트
     */
    @Test
    public void testCreateGame() {
        ensureAuthenticated()
        
        if (!currentRoomId) {
            testCreateRoom()
        }
        
        // 게임에 필요한 최소 4명의 플레이어 구성 (더미 데이터 사용)
        def players = []
        for (int i = 1; i <= 4; i++) {
            def dummyIndex = ((threadNum + i - 1) % 10) + 1
            players.add([
                playerId: "dummy${dummyIndex}",
                isHost: (i == 1)
            ])
        }
        
        def gamePayload = JsonOutput.toJson([
            roomId: currentRoomId,
            players: players
        ])
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/game/create",
            gamePayload.getBytes("UTF-8"),
            headers as NVPair[]
        )
        
        // 게임 생성은 이미 존재하는 경우 실패할 수 있음
        if (response.statusCode == 200) {
            def result = jsonSlurper.parseText(response.getText())
            if (result.success && result.gameId) {
                currentGameId = result.gameId
                grinder.logger.info("게임 생성 성공: ${currentGameId}")
            }
        } else {
            grinder.logger.info("게임 생성 실패 (이미 존재할 수 있음): ${response.statusCode}")
        }
    }
    
    /**
     * 6. 게임 상태 조회 테스트
     * 타이머가 동작 중인 게임의 상태를 조회하여 성능 측정
     */
    @Test
    public void testGameStatus() {
        ensureAuthenticated()
        
        // 게임 ID가 없으면 먼저 생성
        if (!currentGameId) {
            testCreateGame()
        }
        
        if (currentGameId) {
            def headers = [
                new NVPair("Authorization", "Bearer ${jwtToken}"),
                new NVPair("Content-Type", "application/json")
            ]
            
            HTTPResponse response = request.GET(
                "${baseUrl}/api/game/${currentGameId}/status",
                null,
                headers as NVPair[]
            )
            
            assertThat("게임 상태 조회 응답 코드", response.statusCode, is(200))
            
            def result = jsonSlurper.parseText(response.getText())
            assertThat("게임 상태 조회 성공 여부", result.success, is(true))
            
            def game = result.game
            grinder.logger.info("""게임 상태 조회 성공:
                - 게임 ID: ${game?.gameId}
                - 상태: ${game?.status}
                - 남은 시간: ${game?.remainingTime}초
                - 현재 페이즈: ${game?.gamePhase}
                - 낮/밤: ${game?.day ? '낮' : '밤'}
            """)
        } else {
            grinder.logger.info("게임이 없어 상태 조회 스킵")
        }
    }
    
    /**
     * 인증 확인 및 자동 로그인
     */
    private void ensureAuthenticated() {
        if (!jwtToken) {
            testLogin()
        }
    }
}
