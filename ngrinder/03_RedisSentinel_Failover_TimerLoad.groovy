import static net.grinder.script.Grinder.grinder

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import net.grinder.plugin.http.HTTPPluginControl
import net.grinder.plugin.http.HTTPRequest
import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import HTTPClient.HTTPResponse
import HTTPClient.NVPair

/**
 * Scenario 3. Redis Sentinel failover while Redis ZSET timer load is running.
 *
 * nGrinder setting:
 * - Vuser: 50
 * - Duration: 5 minutes
 *
 * Manual fault injection during the run:
 *   docker stop mafiagame_core-redis-master_1
 *
 * Target evidence:
 * - nGrinder TPS drops near zero at master down, then recovers in a V shape.
 * - App logs show Sentinel promotion and Redis reconnection.
 * - Recovery target is within 8.7 seconds.
 */
@RunWith(GrinderRunner)
class RedisSentinelFailoverTimerLoad {

    static String baseUrl
    static int playersPerRoom
    static int maxOutageMs
    static int timerDelayMs

    static GTest registerTest
    static GTest loginTest
    static GTest roomCreateTest
    static GTest roomJoinTest
    static GTest gameCreateTest
    static GTest redisTimerStartTest
    static GTest statusPollTest
    static GTest recoveryProbeTest

    static HTTPRequest request
    static JsonSlurper jsonSlurper

    int threadNumber
    boolean initialized = false
    int consecutiveFailures = 0
    long outageStartedAt = 0L
    String prefix
    String roomId
    String gameId
    String[] loginIds
    String[] tokens

    @BeforeProcess
    static void beforeProcess() {
        baseUrl = property("targetBaseUrl", "http://localhost:8080")
        playersPerRoom = intProperty("playersPerRoom", 4)
        maxOutageMs = intProperty("maxOutageMs", 30000)
        timerDelayMs = intProperty("timerDelayMs", 300000)

        registerTest = new GTest(301, "01_register_users")
        loginTest = new GTest(302, "02_login_users")
        roomCreateTest = new GTest(303, "03_create_room")
        roomJoinTest = new GTest(304, "04_join_room")
        gameCreateTest = new GTest(305, "05_create_game_zset_timer")
        redisTimerStartTest = new GTest(306, "06_start_redis_zset_timer")
        statusPollTest = new GTest(307, "07_poll_status_during_failover")
        recoveryProbeTest = new GTest(308, "08_recovery_probe")

        request = new HTTPRequest()
        jsonSlurper = new JsonSlurper()
        HTTPPluginControl.getConnectionDefaults().timeout = 30000

        grinder.logger.info("[Scenario 3] Redis Sentinel failover timer load target={}", baseUrl)
        grinder.logger.info("[Scenario 3] Inject fault while load is active: docker stop mafiagame_core-redis-master_1")
    }

    @BeforeThread
    void beforeThread() {
        threadNumber = grinder.threadNumber
        grinder.statistics.delayReports = true

        registerTest.record(this, "registerUser")
        loginTest.record(this, "loginUser")
        roomCreateTest.record(this, "createRoom")
        roomJoinTest.record(this, "joinRoom")
        gameCreateTest.record(this, "createGame")
        redisTimerStartTest.record(this, "startRedisZsetTimer")
        statusPollTest.record(this, "pollStatus")
        recoveryProbeTest.record(this, "probeRecovery")
    }

    @Before
    void before() {
        if (!initialized) {
            prefix = uniquePrefix("fo")
            bootstrapGame()
            initialized = true
        }
    }

    @Test
    void pollThroughSentinelFailover() {
        try {
            pollStatus()
            markRecoveredIfNeeded()
        } catch (Throwable failure) {
            markFailure(failure)
            throw failure
        }
    }

    void bootstrapGame() {
        loginIds = new String[playersPerRoom]
        tokens = new String[playersPerRoom]

        for (int i = 0; i < playersPerRoom; i++) {
            loginIds[i] = "${prefix}${i}".toString()
            registerUser(i)
            loginUser(i)
        }

        createRoom()
        for (int i = 1; i < playersPerRoom; i++) {
            joinRoom(i)
        }

        createGame()
        startRedisZsetTimer()
        probeRecovery()
    }

    void registerUser(int playerIndex) {
        String loginId = loginIds[playerIndex]
        String body = JsonOutput.toJson([
                nickname         : "u${threadNumber}${playerIndex}".take(10),
                userLoginId      : loginId,
                userLoginPassword: passwordFor(playerIndex)
        ])

        HTTPResponse response = request.POST("${baseUrl}/api/users/register", bytes(body), jsonHeaders())
        if (!(response.statusCode in [201, 400, 409])) {
            failResponse("register", response)
        }
    }

    void registerUser() { registerUser(0) }

    void loginUser(int playerIndex) {
        String body = JsonOutput.toJson([
                userLoginId      : loginIds[playerIndex],
                userLoginPassword: passwordFor(playerIndex)
        ])

        HTTPResponse response = request.POST("${baseUrl}/api/users/login", bytes(body), jsonHeaders())
        assertStatus("login", response, [200])
        tokens[playerIndex] = jsonSlurper.parseText(response.text).data.token
        assert tokens[playerIndex] != null
    }

    void loginUser() { loginUser(0) }

    void createRoom() {
        String body = JsonOutput.toJson([roomName: "failover-${prefix}"])
        HTTPResponse response = request.POST("${baseUrl}/api/chat/rooms", bytes(body), authHeaders(tokens[0]))
        assertStatus("createRoom", response, [201])
        roomId = jsonSlurper.parseText(response.text).roomId
        assert roomId != null
    }

    void joinRoom(int playerIndex) {
        String body = JsonOutput.toJson([roomId: roomId, userId: loginIds[playerIndex]])
        HTTPResponse response = request.POST("${baseUrl}/api/chat/rooms/${roomId}/join", bytes(body), authHeaders(tokens[playerIndex]))
        assertStatus("joinRoom", response, [200, 204])
    }

    void joinRoom() { joinRoom(1) }

    void createGame() {
        HTTPResponse response = request.POST("${baseUrl}/api/games/create", bytes(""), authHeaders(tokens[0]))
        assertStatus("createGame", response, [200, 201])
        def json = jsonSlurper.parseText(response.text)
        assert json.success == true
        gameId = json.gameId
        assert gameId != null
    }

    void pollStatus() {
        HTTPResponse response = request.GET("${baseUrl}/api/games/${gameId}/status", null, authHeaders(tokens[0]))
        assertStatus("pollStatus", response, [200])

        def json = jsonSlurper.parseText(response.text)
        if (json.success == false || json.game?.status == "ENDED") {
            bootstrapGame()
        }
    }

    void startRedisZsetTimer() {
        long phaseEndTime = System.currentTimeMillis() + timerDelayMs
        String body = JsonOutput.toJson([gameId: gameId, phaseEndTime: phaseEndTime])
        HTTPResponse response = request.POST("${baseUrl}/api/games/test/timer/start", bytes(body), authHeaders(tokens[0]))
        assertStatus("startRedisZsetTimer", response, [200])
    }

    void probeRecovery() {
        HTTPResponse response = request.GET("${baseUrl}/api/games/my-game", null, authHeaders(tokens[0]))
        assertStatus("probeRecovery", response, [200])
    }

    void markFailure(Throwable failure) {
        consecutiveFailures++
        if (outageStartedAt == 0L) {
            outageStartedAt = System.currentTimeMillis()
            grinder.logger.warn("[failover] first failed request at {}", outageStartedAt)
        }

        grinder.logger.warn("[failover] consecutiveFailures={}, cause={}", consecutiveFailures, failure.toString())
        long outageMs = System.currentTimeMillis() - outageStartedAt
        assert outageMs <= maxOutageMs:
                "failover did not recover before maxOutageMs=${maxOutageMs}"
    }

    void markRecoveredIfNeeded() {
        if (outageStartedAt > 0L) {
            long outageMs = System.currentTimeMillis() - outageStartedAt
            grinder.logger.warn("[failover] recovered after {} ms", outageMs)
        }
        outageStartedAt = 0L
        consecutiveFailures = 0
    }

    static String property(String name, String defaultValue) {
        return System.getProperty(name) ?: grinder.properties.getProperty(name, defaultValue)
    }

    static int intProperty(String name, int defaultValue) {
        return property(name, String.valueOf(defaultValue)) as int
    }

    String uniquePrefix(String scenario) {
        String raw = "${scenario}${grinder.processNumber}${threadNumber}${System.currentTimeMillis() % 100000}"
        return raw.take(11)
    }

    String passwordFor(int playerIndex) {
        return "pw${threadNumber}${playerIndex}0000000000".take(20)
    }

    static byte[] bytes(String value) {
        return value.getBytes("UTF-8")
    }

    static NVPair[] jsonHeaders() {
        return [new NVPair("Content-Type", "application/json; charset=UTF-8")] as NVPair[]
    }

    static NVPair[] authHeaders(String token) {
        return [
                new NVPair("Content-Type", "application/json; charset=UTF-8"),
                new NVPair("Authorization", "Bearer ${token}")
        ] as NVPair[]
    }

    static void assertStatus(String step, HTTPResponse response, List<Integer> expected) {
        if (!expected.contains(response.statusCode)) {
            failResponse(step, response)
        }
    }

    static void failResponse(String step, HTTPResponse response) {
        assert false: "${step} failed. status=${response.statusCode}, body=${response.text}"
    }
}
