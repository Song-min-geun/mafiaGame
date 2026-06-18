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
 * Scenario 2. Redis ZSET timer + ZGC steady load.
 *
 * nGrinder setting:
 * - Vuser: 50
 * - Duration: 5 minutes
 *
 * Target evidence:
 * - nGrinder TPS reaches the portfolio target, 3,325 TPS.
 * - Grafana 4701 JVM GC Pause Time stays <= 14 ms.
 *
 * Run the Spring Boot app with the current Redis ZSET timer implementation and
 * ZGC enabled. The script creates one game per Vuser and repeatedly reads game
 * status while the Redis worker advances phases from the ZSET.
 */
@RunWith(GrinderRunner)
class RedisZsetZgcTimerLoad {

    static String baseUrl
    static int playersPerRoom
    static int timerDelayMs

    static GTest registerTest
    static GTest loginTest
    static GTest roomCreateTest
    static GTest roomJoinTest
    static GTest gameCreateTest
    static GTest redisTimerStartTest
    static GTest statusPollTest
    static GTest myGameTest

    static HTTPRequest request
    static JsonSlurper jsonSlurper

    int threadNumber
    boolean initialized = false
    String prefix
    String roomId
    String gameId
    String[] loginIds
    String[] tokens

    @BeforeProcess
    static void beforeProcess() {
        baseUrl = property("targetBaseUrl", "http://localhost:8080")
        playersPerRoom = intProperty("playersPerRoom", 4)
        timerDelayMs = intProperty("timerDelayMs", 300000)

        registerTest = new GTest(201, "01_register_users")
        loginTest = new GTest(202, "02_login_users")
        roomCreateTest = new GTest(203, "03_create_room")
        roomJoinTest = new GTest(204, "04_join_room")
        gameCreateTest = new GTest(205, "05_create_game_zset_timer")
        redisTimerStartTest = new GTest(206, "06_start_redis_zset_timer")
        statusPollTest = new GTest(207, "07_poll_game_status_tps")
        myGameTest = new GTest(208, "08_my_game_read")

        request = new HTTPRequest()
        jsonSlurper = new JsonSlurper()
        HTTPPluginControl.getConnectionDefaults().timeout = 30000

        grinder.logger.info("[Scenario 2] Redis ZSET + ZGC timer load target={}", baseUrl)
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
        myGameTest.record(this, "readMyGame")
    }

    @Before
    void before() {
        if (!initialized) {
            prefix = uniquePrefix("zs")
            bootstrapGame()
            initialized = true
        }
    }

    @Test
    void pollRedisZsetBackedGameStatus() {
        pollStatus()
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
        readMyGame()
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
        String body = JsonOutput.toJson([roomName: "zset-${prefix}"])
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

    void readMyGame() {
        HTTPResponse response = request.GET("${baseUrl}/api/games/my-game", null, authHeaders(tokens[0]))
        assertStatus("readMyGame", response, [200])
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
