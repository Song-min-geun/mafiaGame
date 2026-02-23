/**
 * 전략별 순차 비교 테스트 스크립트
 * 
 * 사용법:
 * 1. 먼저 각 전략별로 별도 유저를 생성해둠
 * 2. 이 스크립트를 nGrinder에 업로드
 * 3. 각 스레드가 다른 전략을 테스트하도록 분배
 * 
 * 주의: 이 스크립트는 데모용이며,
 *       실제 비교 테스트는 각 전략별로 별도 테스트 실행 권장
 */

import static net.grinder.script.Grinder.grinder
import static org.junit.Assert.*
import static org.hamcrest.Matchers.*

import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import HTTPClient.HTTPResponse
import HTTPClient.NVPair

@RunWith(GrinderRunner)
class StrategyComparisonTest {

    public static GTest test
    public static HTTPRequest request
    public static NVPair[] headers = []
    
    static String BASE_URL = "http://localhost:8080"
    
    // 전략 목록
    static String[] LOCK_TYPES = [
        "NONE",
        "SYNCHRONIZED", 
        "PESSIMISTIC", 
        "OPTIMISTIC", 
        "REDISSON_SPIN", 
        "REDISSON_PUBSUB"
    ]
    
    // 각 전략별 테스트 유저 ID (미리 생성 필요)
    static Long[] USER_IDS = [101L, 102L, 103L, 104L, 105L, 106L]
    
    String currentLockType
    Long currentUserId

    @BeforeProcess
    public static void beforeProcess() {
        HTTPPluginControl.getConnectionDefaults().timeout = 6000
        test = new GTest(1, "Strategy Comparison Test")
        request = new HTTPRequest()
        test.record(request)
    }

    @BeforeThread
    public void beforeThread() {
        grinder.statistics.delayReports = true
        
        // 스레드 번호에 따라 전략 할당 (라운드 로빈)
        int strategyIndex = grinder.threadNumber % LOCK_TYPES.length
        currentLockType = LOCK_TYPES[strategyIndex]
        currentUserId = USER_IDS[strategyIndex]
        
        grinder.logger.info("Thread {} assigned to {} (userId={})", 
            grinder.threadNumber, currentLockType, currentUserId)
    }

    @Before
    public void before() {
        // 요청 전 설정
    }

    @Test
    public void testIncrementWithAssignedStrategy() {
        String url = BASE_URL + "/api/test/concurrency/increment?userId=" + currentUserId + "&lockType=" + currentLockType
        
        long startTime = System.currentTimeMillis()
        HTTPResponse result = request.POST(url, [], headers)
        long elapsedTime = System.currentTimeMillis() - startTimeq

        if (result.statusCode == 200) {
            grinder.logger.info("[{}] {} ms - Thread {}", 
                currentLockType, elapsedTime, grinder.threadNumber)
        } else {
            grinder.logger.error("[{}] FAIL: {} - {}", 
                currentLockType, result.statusCode, result.getText())
        }
        
        assertThat(result.statusCode, is(200))
    }
}
