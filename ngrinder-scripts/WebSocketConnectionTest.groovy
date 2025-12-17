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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 마피아 게임 - WebSocket STOMP 연결 테스트
 * 
 * 테스트 목적:
 * - WebSocket 핸드셰이크 성능 측정
 * - STOMP 엔드포인트 연결 테스트
 * - 다수 사용자 동시 연결 시 서버 부하 측정
 * 
 * 참고: nGrinder는 기본적으로 HTTP 테스트에 최적화되어 있으므로
 * WebSocket 핸드셰이크 HTTP 요청을 테스트합니다.
 */
@RunWith(GrinderRunner)
class WebSocketConnectionTest {
    
    public static String baseUrl = "http://localhost:8080"
    public static String wsEndpoint = "/ws"
    
    public static GTest wsHandshakeTest
    public static GTest loginTest
    public static GTest multipleConnectionTest
    
    public static HTTPRequest request
    public static JsonSlurper jsonSlurper
    public static AtomicInteger totalConnections
    
    String jwtToken
    String currentUserId
    int threadNum
    
    @BeforeProcess
    public static void beforeProcess() {
        wsHandshakeTest = new GTest(1, "WebSocket 핸드셰이크")
        loginTest = new GTest(2, "로그인")
        multipleConnectionTest = new GTest(3, "다중 연결 테스트")
        
        request = new HTTPRequest()
        jsonSlurper = new JsonSlurper()
        totalConnections = new AtomicInteger(0)
        
        HTTPPluginControl.getConnectionDefaults().timeout = 30000
        
        grinder.logger.info("WebSocket 연결 테스트 초기화 완료")
    }
    
    @BeforeThread
    public void beforeThread() {
        threadNum = grinder.threadNumber
        currentUserId = "dummy${(threadNum % 10) + 1}"
        
        wsHandshakeTest.record(this, "testWsHandshake")
        loginTest.record(this, "testLogin")
        multipleConnectionTest.record(this, "testMultipleConnections")
        
        grinder.statistics.delayReports = true
        grinder.logger.info("스레드 ${threadNum} 초기화 - 사용자: ${currentUserId}")
    }
    
    @Before
    public void before() {
        // 각 테스트 전 초기화
    }
    
    /**
     * 로그인 테스트
     */
    @Test
    public void testLogin() {
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
        
        assertThat("로그인 응답 코드", response.statusCode, is(200))
        
        def result = jsonSlurper.parseText(response.getText())
        if (result.success && result.data?.token) {
            jwtToken = result.data.token
            grinder.logger.info("로그인 성공: ${currentUserId}")
        }
    }
    
    /**
     * WebSocket 핸드셰이크 테스트 (SockJS)
     * SockJS는 /ws/info 엔드포인트로 서버 정보를 먼저 조회합니다.
     */
    @Test
    public void testWsHandshake() {
        ensureAuthenticated()
        
        // SockJS info 엔드포인트 호출 (실제 WebSocket 연결 전 단계)
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Accept", "application/json")
        ]
        
        long startTime = System.currentTimeMillis()
        
        HTTPResponse response = request.GET(
            "${baseUrl}${wsEndpoint}/info",
            null,
            headers as NVPair[]
        )
        
        long responseTime = System.currentTimeMillis() - startTime
        
        // SockJS info 응답 확인
        if (response.statusCode == 200) {
            def info = jsonSlurper.parseText(response.getText())
            int connCount = totalConnections.incrementAndGet()
            
            grinder.logger.info("""WebSocket 핸드셰이크 성공:
                - 응답 시간: ${responseTime}ms
                - WebSocket 지원: ${info?.websocket}
                - 총 연결 수: ${connCount}
            """)
        } else {
            grinder.logger.warn("WebSocket info 조회 실패: ${response.statusCode}")
        }
    }
    
    /**
     * 다중 연결 시뮬레이션
     * 한 스레드에서 여러 번의 연결 시도를 시뮬레이션
     */
    @Test
    public void testMultipleConnections() {
        ensureAuthenticated()
        
        def headers = [
            new NVPair("Authorization", "Bearer ${jwtToken}"),
            new NVPair("Accept", "application/json")
        ]
        
        List<Long> responseTimes = []
        int successCount = 0
        int failCount = 0
        
        // 10번의 연속 연결 시도
        for (int i = 0; i < 10; i++) {
            try {
                long startTime = System.currentTimeMillis()
                
                HTTPResponse response = request.GET(
                    "${baseUrl}${wsEndpoint}/info?t=${System.currentTimeMillis()}",
                    null,
                    headers as NVPair[]
                )
                
                long responseTime = System.currentTimeMillis() - startTime
                responseTimes.add(responseTime)
                
                if (response.statusCode == 200) {
                    successCount++
                } else {
                    failCount++
                }
                
                // 짧은 대기
                Thread.sleep(100)
                
            } catch (Exception e) {
                failCount++
                grinder.logger.warn("연결 실패: ${e.message}")
            }
        }
        
        // 결과 통계
        if (responseTimes.size() > 0) {
            def avg = responseTimes.sum() / responseTimes.size()
            def max = responseTimes.max()
            def min = responseTimes.min()
            
            grinder.logger.info("""다중 연결 테스트 완료:
                - 성공: ${successCount}회
                - 실패: ${failCount}회
                - 평균 응답시간: ${avg}ms
                - 최대 응답시간: ${max}ms
                - 최소 응답시간: ${min}ms
            """)
        }
    }
    
    /**
     * 인증 확인
     */
    private void ensureAuthenticated() {
        if (!jwtToken) {
            testLogin()
        }
    }
}
