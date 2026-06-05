package com.example.mafiagame.chat.controller;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.chat.dto.request.CreateRoomRequest;
import com.example.mafiagame.chat.dto.request.JoinRoomRequest;
import com.example.mafiagame.chat.service.ChatRoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = ChatRoomRestController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class
    },
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            com.example.mafiagame.global.config.SecurityConfig.class,
            com.example.mafiagame.global.jwt.JwtRequestFilter.class,
            com.example.mafiagame.global.oauth2.CustomOAuth2UserService.class,
            com.example.mafiagame.global.oauth2.OAuth2SuccessHandler.class
        }
    )
)
@AutoConfigureMockMvc(addFilters = false)
class ChatRoomRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatRoomService chatRoomService;

    private ChatRoom chatRoom;

    @BeforeEach
    void setUp() {
        chatRoom = ChatRoom.builder()
                .roomId("room-123")
                .roomName("테스트 마피아 방")
                .hostId("testUser")
                .hostName("방장닉네임")
                .participants(new ArrayList<>(List.of(
                        ChatUser.builder().userId("testUser").userName("방장닉네임").isHost(true).build()
                )))
                .build();
    }

    @Test
    @DisplayName("채팅방 생성 - 성공")
    void createRoom_success() throws Exception {
        // given
        CreateRoomRequest request = new CreateRoomRequest("테스트 마피아 방");
        when(chatRoomService.createRoom(any(CreateRoomRequest.class), eq("testUser"))).thenReturn(chatRoom);

        // when & then
        mockMvc.perform(post("/api/chat/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(new UsernamePasswordAuthenticationToken("testUser", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value("room-123"))
                .andExpect(jsonPath("$.roomName").value("테스트 마피아 방"))
                .andExpect(jsonPath("$.hostId").value("testUser"))
                .andExpect(jsonPath("$.hostName").value("방장닉네임"));

        verify(chatRoomService).createRoom(any(CreateRoomRequest.class), eq("testUser"));
    }

    @Test
    @DisplayName("채팅방 입장 - 성공")
    void joinRoom_success() throws Exception {
        // given
        JoinRoomRequest request = new JoinRoomRequest("room-123", "testUser");

        // when & then
        mockMvc.perform(post("/api/chat/rooms/room-123/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(new UsernamePasswordAuthenticationToken("testUser", "")))
                .andExpect(status().isOk());

        verify(chatRoomService).userJoin(any(JoinRoomRequest.class));
    }

    @Test
    @DisplayName("채팅방 입장 - 방 ID 불일치로 예외 발생")
    void joinRoom_mismatchedRoomId() throws Exception {
        // given
        JoinRoomRequest request = new JoinRoomRequest("mismatched-room", "testUser");

        // when & then
        mockMvc.perform(post("/api/chat/rooms/room-123/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(new UsernamePasswordAuthenticationToken("testUser", "")))
                .andExpect(status().isInternalServerError());

        verify(chatRoomService, never()).userJoin(any());
    }

    @Test
    @DisplayName("채팅방 목록 조회 - 성공")
    void getAllRooms_success() throws Exception {
        // given
        when(chatRoomService.getAllRooms()).thenReturn(List.of(chatRoom));

        // when & then
        mockMvc.perform(get("/api/chat/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].roomId").value("room-123"))
                .andExpect(jsonPath("$[0].roomName").value("테스트 마피아 방"));

        verify(chatRoomService).getAllRooms();
    }

    @Test
    @DisplayName("채팅방 조회 - 존재할 때")
    void getRoom_found() throws Exception {
        // given
        when(chatRoomService.getRoom("room-123")).thenReturn(chatRoom);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/room-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("room-123"))
                .andExpect(jsonPath("$.roomName").value("테스트 마피아 방"));

        verify(chatRoomService).getRoom("room-123");
    }

    @Test
    @DisplayName("채팅방 조회 - 존재하지 않을 때 404")
    void getRoom_notFound() throws Exception {
        // given
        when(chatRoomService.getRoom("non-existent")).thenReturn(null);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/non-existent"))
                .andExpect(status().isNotFound());

        verify(chatRoomService).getRoom("non-existent");
    }

    @Test
    @DisplayName("채팅방 검색 - 성공")
    void searchRooms_success() throws Exception {
        // given
        when(chatRoomService.searchRooms("테스트")).thenReturn(List.of(chatRoom));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/search")
                        .param("keyword", "테스트"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].roomId").value("room-123"));

        verify(chatRoomService).searchRooms("테스트");
    }
}
