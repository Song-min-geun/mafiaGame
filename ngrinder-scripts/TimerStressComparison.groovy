import static net.grinder.script.Grinder.grinder
import static org.junit.Assert.*
import static org.hamcrest.Matchers.*
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPPluginControl
import net.grinder.script.GTest
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
 * 타이머 스트레스 비교 테스트
 * 
 * 목적: @Scheduled vs Redis TTL 타이머 성능 비교
 * 
 * 측정 항목:
 * - 동시 게임 수 증가에 따른 TPS
 * - 게임 상태 조회 응답 시간
 * 
 * 사용법:
 * 1. @Scheduled 방식으로 서버 실행 후 테스트
 * 2. Redis TTL 방식으로 서버 실행 후 동일 테스트
 * 3. 결과 TPS 비교
 */
@RunWith(GrinderRunner)
class TimerStressComparison {
    
    public static String baseUrl = "http://localhost:8080"
    
    public static GTest createGameTest
    public static GTest gameStatusTest
    
    public static HTTPRequest request
    public static JsonSlurper jsonSlurper
    
    String jwtToken
    String currentUserId
    String currentRoomId
    String currentGameId
    int threadNum
    boolean initialized = false
    
    @BeforeProcess
    public static void beforeProcess() {
        createGameTest = new GTest(1, "게임 생성")
        gameStatusTest = new GTest(2, "게임 상태 조회 (TPS 측정)")
        
        request = new HTTPRequest()
        jsonSlurper = new JsonSlurper()
        HTTPPluginControl.getConnectionDefaults().timeout = 30000
        
        grinder.logger.info("타이머 스트레스 테스트 초기화")
    }
    
    @BeforeThread
    public void beforeThread() {
        threadNum = grinder.threadNumber
        currentUserId = "dummy${(threadNum % 100) + 1}"
        
        createGameTest.record(this, "createGame")
        gameStatusTest.record(this, "pollGameStatus")
        
        grinder.statistics.delayReports = true
    }
    
    @Before
    public void before() {
        // 첫 실행 시에만 로그인 + 방 생성 + 게임 생성
        if (!initialized) {
            login()
            createRoom()
            createGame()
            initialized = true
        }
    }
    
    /**
     * 메인 테스트 - 게임 상태 폴링 (TPS 측정 대상)
     */
    @Test
    public void testTimerPerformance() {
        if (currentGameId) {
            pollGameStatus()
        }
    }
    
    /**
     * 로그인
     */
    private void login() {
        def payload = JsonOutput.toJson([
            userLoginId: currentUserId,
            userLoginPassword: "password1234!"
        ])
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/users/login",
            payload.getBytes("UTF-8"),
            [new NVPair("Content-Type", "application/json")] as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def result = jsonSlurper.parseText(response.getText())
            jwtToken = result.data?.token
            grinder.logger.info("로그인 성공: ${currentUserId}")
        }
    }
    
    /**
     * 채팅방 생성
     */
    private void createRoom() {
        def payload = JsonOutput.toJson([
            roomName: "StressTest_${threadNum}_${System.currentTimeMillis()}",
            userId: currentUserId
        ])
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/chat/rooms",
            payload.getBytes("UTF-8"),
            [
                new NVPair("Authorization", "Bearer ${jwtToken}"),
                new NVPair("Content-Type", "application/json")
            ] as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def room = jsonSlurper.parseText(response.getText())
            currentRoomId = room?.roomId
            grinder.logger.info("방 생성: ${currentRoomId}")
        }
    }
    
    /**
     * 게임 생성 (타이머 활성화)
     */
    public void createGame() {
        if (!currentRoomId) return
        
        // 4명 플레이어 구성
        def players = []
        for (int i = 1; i <= 4; i++) {
            def idx = ((threadNum + i - 1) % 10) + 1
            players.add([playerId: "dummy${idx}", isHost: (i == 1)])
        }
        
        def payload = JsonOutput.toJson([
            roomId: currentRoomId,
            players: players
        ])
        
        HTTPResponse response = request.POST(
            "${baseUrl}/api/game/create",
            payload.getBytes("UTF-8"),
            [
                new NVPair("Authorization", "Bearer ${jwtToken}"),
                new NVPair("Content-Type", "application/json")
            ] as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def result = jsonSlurper.parseText(response.getText())
            currentGameId = result?.gameId
            grinder.logger.info("게임 생성: ${currentGameId} (타이머 시작됨)")
        }
    }
    
    /**
     * 게임 상태 폴링 - TPS 측정 핵심
     * 
     * 이 메서드의 TPS가 타이머 성능을 나타냄
     * - @Scheduled: 1초마다 모든 게임 순회
     * - Redis TTL: 개별 게임 TTL 만료 시 콜백
     */
    public void pollGameStatus() {
        HTTPResponse response = request.GET(
            "${baseUrl}/api/game/${currentGameId}/status",
            null,
            [
                new NVPair("Authorization", "Bearer ${jwtToken}"),
                new NVPair("Content-Type", "application/json")
            ] as NVPair[]
        )
        
        if (response.statusCode == 200) {
            def result = jsonSlurper.parseText(response.getText())
            def game = result.game
            
            // 게임 종료 시 새 게임 생성
            if (game?.status == "ENDED") {
                createRoom()
                createGame()
            }
        }
    }
}
