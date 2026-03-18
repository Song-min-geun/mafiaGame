package com.example.mafiagame.integration;

import com.example.mafiagame.support.RedisTestContainerSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatRoomIntegrationTest extends RedisTestContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Create room -> Join -> Participant list updated")
    void createRoomAndJoin() throws Exception {
        TestUser host = registerAndLogin("host");
        TestUser guest = registerAndLogin("guest");

        // 1) Create room (host)
        Map<String, String> createRoomBody = Map.of("roomName", "room-" + host.loginId);
        MvcResult createRoomResult = mockMvc.perform(post("/api/chat/rooms")
                        .header("Authorization", "Bearer " + host.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRoomBody)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode roomJson = objectMapper.readTree(createRoomResult.getResponse().getContentAsString());
        String roomId = roomJson.path("roomId").asText();
        assertThat(roomId).isNotBlank();

        // 2) Guest joins
        Map<String, String> joinRoomBody = Map.of(
                "roomId", roomId,
                "userId", "ignored");
        mockMvc.perform(post("/api/chat/rooms/" + roomId + "/join")
                        .header("Authorization", "Bearer " + guest.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinRoomBody)))
                .andExpect(status().isOk());

        // 3) Fetch room -> verify 2 participants
        MvcResult getRoomResult = mockMvc.perform(get("/api/chat/rooms/" + roomId)
                        .header("Authorization", "Bearer " + host.accessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode getRoomJson = objectMapper.readTree(getRoomResult.getResponse().getContentAsString());
        JsonNode participants = getRoomJson.path("participants");

        assertThat(participants.isArray()).isTrue();
        assertThat(participants.size()).isEqualTo(2);
    }

    private TestUser registerAndLogin(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String loginId = prefix + suffix;
        String nickname = "n" + suffix;
        String password = "password1234!";

        Map<String, String> registerBody = Map.of(
                "nickname", nickname,
                "userLoginId", loginId,
                "userLoginPassword", password);

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated());

        Map<String, String> loginBody = Map.of(
                "userLoginId", loginId,
                "userLoginPassword", password);

        MvcResult loginResult = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginJson.path("data").path("token").asText();

        assertThat(accessToken).isNotBlank();
        return new TestUser(loginId, accessToken);
    }

    private record TestUser(String loginId, String accessToken) {
    }
}
