package com.example.mafiagame.acceptance;

import com.example.mafiagame.support.RedisTestContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameFlowAcceptanceTest extends RedisTestContainerSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Register -> Login -> Create/Join room -> Create game -> Check status")
    void endToEndGameFlow() {
        TestUser host = registerAndLogin("host");
        TestUser user2 = registerAndLogin("user");
        TestUser user3 = registerAndLogin("user");
        TestUser user4 = registerAndLogin("user");

        // 1) Create room (host)
        String roomId = createRoom(host.accessToken);
        assertThat(roomId).isNotBlank();

        // 2) Other users join
        joinRoom(roomId, user2.accessToken);
        joinRoom(roomId, user3.accessToken);
        joinRoom(roomId, user4.accessToken);

        // 3) Create game
        Map<String, Object> createGame = postWithAuth("/api/games/create", host.accessToken, null);
        assertThat(createGame.get("success")).isEqualTo(true);
        String gameId = (String) createGame.get("gameId");
        assertThat(gameId).isNotBlank();

        // 4) Check game status
        Map<String, Object> gameStatus = getWithAuth("/api/games/" + gameId + "/status", host.accessToken);
        assertThat(gameStatus.get("success")).isEqualTo(true);
        assertThat(gameStatus.get("game")).isNotNull();

        // 5) Participant can see current game
        Map<String, Object> myGame = getWithAuth("/api/games/my-game", user2.accessToken);
        assertThat(myGame.get("success")).isEqualTo(true);
        assertThat(myGame.get("data")).isNotNull();
    }

    @Test
    @DisplayName("ZSET worker advances due game timers")
    void dueTimerAdvancesPhase() throws InterruptedException {
        TestUser host = registerAndLogin("host");
        TestUser user2 = registerAndLogin("user");
        TestUser user3 = registerAndLogin("user");
        TestUser user4 = registerAndLogin("user");

        String roomId = createRoom(host.accessToken);
        joinRoom(roomId, user2.accessToken);
        joinRoom(roomId, user3.accessToken);
        joinRoom(roomId, user4.accessToken);

        Map<String, Object> createGame = postWithAuth("/api/games/create", host.accessToken, null);
        String gameId = (String) createGame.get("gameId");

        postWithAuthVoid("/api/games/test/timer/start", host.accessToken, Map.of(
                "gameId", gameId,
                "phaseEndTime", System.currentTimeMillis() + 300L));

        Map<String, Object> finalStatus = awaitGamePhase(gameId, host.accessToken, "DAY_DISCUSSION", 5_000L);
        Map<String, Object> game = (Map<String, Object>) finalStatus.get("game");

        assertThat(game.get("gamePhase")).isEqualTo("DAY_DISCUSSION");
        assertThat(((Number) game.get("currentPhase")).intValue()).isEqualTo(2);
    }

    private TestUser registerAndLogin(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String loginId = prefix + suffix;
        String nickname = "n" + suffix;
        String password = "password1234!";

        Map<String, String> registerBody = Map.of(
                "nickname", nickname,
                "userLoginId", loginId,
                "userLoginPassword", password);
        ResponseEntity<Map> registerResponse = restTemplate.postForEntity(
                url("/api/users/register"),
                registerBody,
                Map.class);

        assertThat(registerResponse.getStatusCode().is2xxSuccessful()).isTrue();

        Map<String, String> loginBody = Map.of(
                "userLoginId", loginId,
                "userLoginPassword", password);
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                url("/api/users/login"),
                loginBody,
                Map.class);

        assertThat(loginResponse.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> loginData = (Map<String, Object>) loginResponse.getBody().get("data");
        String accessToken = (String) loginData.get("token");
        assertThat(accessToken).isNotBlank();

        return new TestUser(loginId, accessToken);
    }

    private String createRoom(String accessToken) {
        Map<String, String> body = Map.of("roomName", "room-" + UUID.randomUUID());
        Map<String, Object> response = postWithAuth("/api/chat/rooms", accessToken, body);
        return (String) response.get("roomId");
    }

    private void joinRoom(String roomId, String accessToken) {
        Map<String, String> body = Map.of(
                "roomId", roomId,
                "userId", "ignored");
        postWithAuthVoid("/api/chat/rooms/" + roomId + "/join", accessToken, body);
    }

    private Map<String, Object> postWithAuth(String path, String accessToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                url(path),
                HttpMethod.POST,
                entity,
                Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private Map<String, Object> getWithAuth(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                url(path),
                HttpMethod.GET,
                entity,
                Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private void postWithAuthVoid(String path, String accessToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Void> response = restTemplate.exchange(
                url(path),
                HttpMethod.POST,
                entity,
                Void.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitGamePhase(String gameId, String accessToken, String expectedPhase, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Map<String, Object> status = getWithAuth("/api/games/" + gameId + "/status", accessToken);
            Map<String, Object> game = (Map<String, Object>) status.get("game");
            if (game != null && expectedPhase.equals(game.get("gamePhase"))) {
                return status;
            }
            Thread.sleep(100L);
        }

        return getWithAuth("/api/games/" + gameId + "/status", accessToken);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private record TestUser(String loginId, String accessToken) {
    }
}
