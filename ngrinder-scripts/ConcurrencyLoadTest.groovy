/**
 * 동시성 제어 전략별 부하 테스트 스크립트
 * 
 * 사용법:
 * 1. nGrinder 콘솔에 업로드
 * 2. LOCK_TYPE 변수를 변경하여 테스트 대상 전략 선택
 * 3. Virtual User, Duration 설정 후 실행
 * 
 * 테스트 시나리오:
 * - 동일한 유저에게 동시 playCount 증가 요청
 * - 정합성: 테스트 후 playCount == 총 요청 수 확인
 * 
 * 포트폴리오 포인트:
 * - 각 전략별로 별도 테스트 실행
 * - 결과 비교표 완성
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
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPPluginControl

@RunWith(GrinderRunner)
class ConcurrencyLoadTest {

    public static GTest test
    public static HTTPRequest request
    public static NVPair[] headers = []
    
    // ============================================
    // 테스트 설정 - 이 값들을 변경하세요
    // ============================================
    
    // 대상 서버 URL
    // ⚠️ nGrinder Agent가 접근할 수 있는 IP로 설정
    // localhost는 Agent에서 접근 불가!
    static String BASE_URL = "http://localhost:8080"
    
    // 테스트할 락 타입
    // NONE, SYNCHRONIZED, PESSIMISTIC, OPTIMISTIC, REDISSON_SPIN, REDISSON_PUBSUB
    static String LOCK_TYPE = "SYNCHRONIZED"
    
    // 테스트 대상 유저 ID (동시성 테스트를 위해 고정)
    static Long USER_ID = 1L

    @BeforeProcess
    public static void beforeProcess() {
        HTTPPluginControl.getConnectionDefaults().timeout = 6000
        test = new GTest(1, "Concurrency Test: " + LOCK_TYPE)
        request = new HTTPRequest()
        test.record(request)
        grinder.logger.info("Lock Type: {}", LOCK_TYPE)
    }

    @BeforeThread
    public void beforeThread() {
        grinder.statistics.delayReports = true
        grinder.logger.info("Thread {} started", grinder.threadNumber)
    }

    @Before
    public void before() {
        // 요청 전 설정
    }

    @Test
    public void testIncrementPlayCount() {
        String url = BASE_URL + "/api/test/concurrency/increment?userId=" + USER_ID + "&lockType=" + LOCK_TYPE
        
        // POST(url, formData) - formData는 NVPair[] 타입이어야 함
        NVPair[] formData = new NVPair[0]
        HTTPResponse result = request.POST(url, formData)

        if (result.statusCode == 200) {
            grinder.logger.info("[SUCCESS] Thread {}: {}", grinder.threadNumber, result.getText())
        } else if (result.statusCode == 301 || result.statusCode == 302) {
            grinder.logger.warn("Redirect: {}", result.getHeader("Location"))
        } else {
            grinder.logger.error("[FAIL] Thread {}: {} - {}", 
                grinder.threadNumber, result.statusCode, result.getText())
        }
        
        assertThat(result.statusCode, is(200))
    }
}
