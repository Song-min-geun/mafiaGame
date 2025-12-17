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
 * 마피아 게임 - 다중 게임 동시 진행 타이머 부하 테스트
 * 
 * 테스트 목적:
 * - 중앙 집중식 @Scheduled 타이머가 다수의 게임을 동시에 처리할 때의 성능 측정
 * - 동시에 N개의 게임이 진행될 때 타이머 업데이트 지연 여부 확인
 * - API 응답 시간 측정
 * 
 * 테스트 시나리오:
 * 1. 각 스레드가 개별 방 생성 → 게임 시작
 * 2. 주기적으로 게임 상태 폴링
 * 3. 응답 시간 및 에러율 측정
 */
@RunWith(GrinderRunner)
class TimerLoadTest {
    
    // 테스트 대상 서버 설정
    public static String baseUrl = "http://localhost:8080"
    
    public static GTest loginTest
    public static GTest createRoomTest
    public static GTest createGameTest
    public static GTest pollGameStatusTest
    public static GTest continuousPollingTest
    
    public static HTTPRequest request
    public static JsonSlurper jsonSlurper
    
    // 스레드별 변수
    String jwtToken
    String currentUserId
    String currentRoomId
    String currentGameId
    int threadNum
    int runCount = 0
    
    @BeforeProcess
    public static void beforeProcess() {
        loginTest = new GTest(1, "로그인")
        createRoomTest = new GTest(2, "채팅방 생성")
        createGameTest = new GTest(3, "게임 생성 및 시작")
        pollGameStatusTest = new GTest(4, "게임 상태 폴링")
        continuousPollingTest = new GTest(5, "연속 폴링 테스트")
        
        request = new HTTPRequest()
        jsonSlurper = new JsonSlurper()
        
        HTTPPluginControl.getConnectionDefaults().timeout = 30000
        
        grinder.logger.info("타이머 부하 테스트 초기화 완료")
    }
    
    @BeforeThread
    public void beforeThread() {
        threadNum = grinder.threadNumber
        
        loginTest.record(this, "login")
        createRoomTest.record(this, "createRoom")
        createGameTest.record(this, "createGame")
        pollGameStatusTest.record(this, "pollGameStatus")
        continuousPollingTest.record(this, "continuousPolling")
        
        grinder.statistics.delayReports = true
        
        // 더미 사용자 할당 (dummy1 ~ dummy10)
        currentUserId = "dummy${(threadNum % 10) + 1}"
        
        grinder.logger.info("스레드 ${threadNum} 초기화 - 사용자: ${currentUserId}")
    }
    
    @Before
    public void before() {
        runCount++
    }
    
    /**
     * 메인 테스트 - 타이머 부하 시나리오
     */
    @Test
    public void testTimerLoad() {
        // 첫 실행 시 로그인 및 게임 생성
        if (runCount == 1) {
            login()
            createRoom()
            createGame()
        }
        
        // 게임이 존재하면 연속 폴링
        if (currentGameId) {
            continuousPolling()
        } else {
            // 게임이 없으면 상태 조회 대신 방 목록 조회
            pollRoomList()
        }
    }
    
    /**
     * 로그인
     */
    public void login() {
        def payload = JsonOutput.toJson([
            userLoginId: currentUserId,
            userLoginPassword: "password1234!"
        ])
        
        def headers = [
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/users/login",
            payload.getBytes("UTF-8"),
            headers as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def result = jsonSlurper.parseText(response.getText())
            if (result.success && result.data?.token) {
                jwtToken = result.data.token
                grinder.logger.info("로그인 성공: ${currentUserId}")
            }
        } else {
            grinder.logger.error("로그인 실패: ${response.statusCode}")
        }
    }
    
    /**
     * 채팅방 생성
     */
    public void createRoom() {
        if (!jwtToken) {
            login()
        }
        
        def payload = JsonOutput.toJson([
            roomName: "LoadTest_${threadNum}_${System.currentTimeMillis()}",
            userId: currentUserId
        ])
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/chat/rooms",
            payload.getBytes("UTF-8"),
            headers as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def room = jsonSlurper.parseText(response.getText())
            if (room?.roomId) {
                currentRoomId = room.roomId
                grinder.logger.info("채팅방 생성 성공: ${currentRoomId}")
            }
        } else {
            grinder.logger.error("채팅방 생성 실패: ${response.statusCode}")
        }
    }
    
    /**
     * 게임 생성
     */
    public void createGame() {
        if (!jwtToken || !currentRoomId) {
            grinder.logger.warn("토큰 또는 방 ID가 없어 게임 생성 스킵")
            return
        }
        
        // 4명의 더미 플레이어 구성
        def players = []
        for (int i = 1; i <= 4; i++) {
            def dummyIndex = ((threadNum + i - 1) % 10) + 1
            players.add([
                playerId: "dummy${dummyIndex}",
                isHost: (i == 1)
            ])
        }
        
        def payload = JsonOutput.toJson([
            roomId: currentRoomId,
            players: players
        ])
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/game/create",
            payload.getBytes("UTF-8"),
            headers as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def result = jsonSlurper.parseText(response.getText())
            if (result.success && result.gameId) {
                currentGameId = result.gameId
                grinder.logger.info("게임 생성 성공: ${currentGameId}")
            }
        } else {
            grinder.logger.warn("게임 생성 실패: ${response.statusCode}")
        }
    }
    
    /**
     * 게임 상태 단일 폴링
     */
    public void pollGameStatus() {
        if (!jwtToken || !currentGameId) {
            return
        }
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        long startTime = System.currentTimeMillis()
        
        HTTPResponse response = request.GET(
            "${baseUrl}/api/game/${currentGameId}/status",
            null,
            headers as NVPair[]
        )
        
        long responseTime = System.currentTimeMillis() - startTime
        
        if (response.statusCode == 200) {
            def result = jsonSlurper.parseText(response.getText())
            def game = result.game
            grinder.logger.info("""게임 상태 폴링 - 
                응답시간: ${responseTime}ms, 
                남은시간: ${game?.remainingTime}초, 
                페이즈: ${game?.gamePhase}""")
        } else {
            grinder.logger.warn("게임 상태 조회 실패: ${response.statusCode}")
        }
    }
    
    /**
     * 연속 폴링 테스트 (타이머 간격과 유사하게 1초 간격으로 5회)
     */
    public void continuousPolling() {
        if (!jwtToken || !currentGameId) {
            grinder.logger.info("게임 ID가 없어 연속 폴링 스킵")
            return
        }
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        List<Long> responseTimes = []
        
        // 1초 간격으로 5회 폴링
        for (int i = 0; i < 5; i++) {
            long startTime = System.currentTimeMillis()
            
            HTTPResponse response = request.GET(
                "${baseUrl}/api/game/${currentGameId}/status",
                null,
                headers as NVPair[]
            )
            
            long responseTime = System.currentTimeMillis() - startTime
            responseTimes.add(responseTime)
            
            if (response.statusCode == 200) {
                def result = jsonSlurper.parseText(response.getText())
                def game = result.game
                
                // 게임이 종료되었으면 중단
                if (game?.status == "ENDED") {
                    grinder.logger.info("게임 종료됨: ${currentGameId}")
                    currentGameId = null
                    break
                }
            }
            
            // 1초 대기 (타이머 주기와 동일)
            if (i < 4) {
                Thread.sleep(1000)
            }
        }
        
        // 응답 시간 통계
        if (responseTimes.size() > 0) {
            def avg = responseTimes.sum() / responseTimes.size()
            def max = responseTimes.max()
            def min = responseTimes.min()
            
            grinder.logger.info("""연속 폴링 완료:
                - 폴링 횟수: ${responseTimes.size()}
                - 평균 응답시간: ${avg}ms
                - 최대 응답시간: ${max}ms
                - 최소 응답시간: ${min}ms
            """)
        }
    }
    
    /**
     * 방 목록 폴링 (게임이 없을 때)
     */
    public void pollRoomList() {
        if (!jwtToken) {
            login()
        }
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Content-Type", "application/json")
        ]
        
        HTTPResponse response = request.GET(
            "${baseUrl}/api/chat/rooms",
            null,
            headers as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def rooms = jsonSlurper.parseText(response.getText())
            grinder.logger.info("방 목록 조회: ${rooms?.size() ?: 0}개")
        }
    }
}
