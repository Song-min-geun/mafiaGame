package com.example.mafiagame.chat.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.chat.dto.MessageType;
import com.example.mafiagame.chat.dto.request.CreateRoomRequest;
import com.example.mafiagame.chat.dto.request.JoinRoomRequest;
import com.example.mafiagame.chat.dto.request.LeaveRoomRequest;
import com.example.mafiagame.game.domain.entity.Game;
import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GamePlayerState;
import com.example.mafiagame.game.domain.state.PlayerRole;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.service.GameQueryService;
import com.example.mafiagame.game.service.SuggestionService;
import com.example.mafiagame.global.error.CommonException;
import com.example.mafiagame.global.error.ErrorCode;
import com.example.mafiagame.global.service.RedisService;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private GameQueryService gameQueryService;
    @Mock
    private WebSocketMessageBroadcaster messageBroadcaster;
    @Mock
    private SuggestionService suggestionService;
    @Mock
    private GameStateRepository gameStateRepository;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RedisService redisService;

    @Mock
    private RLock rLock;
    @Mock
    private ListOperations<String, String> listOperations;

    @InjectMocks
    private ChatRoomService chatRoomService;

    private Users host;
    private Users guest;
    private ChatRoom chatRoom;

    @BeforeEach
    void setUp() {
        host = Users.builder()
                .userId(1L)
                .userLoginId("hostUser")
                .nickname("방장닉네임")
                .build();

        guest = Users.builder()
                .userId(2L)
                .userLoginId("guestUser")
                .nickname("게스트닉네임")
                .build();

        chatRoom = new ChatRoom("테스트방", host.getUserLoginId(), host.getNickname());
        chatRoom.setRoomId("room-123");
        chatRoom.addParticipant(ChatUser.builder()
                .userId(host.getUserLoginId())
                .userName(host.getNickname())
                .isHost(true)
                .build());
    }

    // ================== createRoom Tests ================== //

    @Test
    @DisplayName("방 생성 - 성공")
    void createRoom_success() {
        // given
        CreateRoomRequest request = new CreateRoomRequest("새로운 마피아 방");
        when(userService.getUserByLoginId("hostUser")).thenReturn(host);

        // when
        ChatRoom createdRoom = chatRoomService.createRoom(request, "hostUser");

        // then
        assertThat(createdRoom).isNotNull();
        assertThat(createdRoom.getRoomName()).isEqualTo("새로운 마피아 방");
        assertThat(createdRoom.getHostId()).isEqualTo("hostUser");
        assertThat(createdRoom.getParticipants()).hasSize(1);
        assertThat(createdRoom.getParticipants().get(0).getUserId()).isEqualTo("hostUser");
        verify(redisService).saveChatRoom(any(ChatRoom.class));
        verify(redisService).saveUserSession(eq("hostUser"), eq(createdRoom.getRoomId()), any());
    }

    @Test
    @DisplayName("방 생성 - 세션 저장 중 예외 발생 시 방 삭제 및 예외 던짐")
    void createRoom_rollbackOnSessionError() {
        // given
        CreateRoomRequest request = new CreateRoomRequest("새로운 마피아 방");
        when(userService.getUserByLoginId("hostUser")).thenReturn(host);
        doThrow(new RuntimeException("Redis error")).when(redisService).saveUserSession(anyString(), anyString(), any());

        // when & then
        assertThatThrownBy(() -> chatRoomService.createRoom(request, "hostUser"))
                .isInstanceOf(CommonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_CREATE_FAILED);

        verify(redisService).deleteChatRoom(anyString());
    }

    // ================== userJoin Tests ================== //

    @Test
    @DisplayName("방 입장 - 방 정보가 없는 경우(null/공백)")
    void userJoin_invalidRoomId() {
        // given
        JoinRoomRequest request = new JoinRoomRequest("   ", "guestUser");

        // when
        chatRoomService.userJoin(request);

        // then
        verify(messageBroadcaster).sendError("guestUser", "방 정보가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("방 입장 - 이미 다른 방에 속해 있는 경우")
    void userJoin_alreadyInAnotherRoom() {
        // given
        JoinRoomRequest request = new JoinRoomRequest("room-123", "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-456");

        // when
        chatRoomService.userJoin(request);

        // then
        verify(messageBroadcaster).sendError("guestUser", "이미 다른 방에 참여 중입니다.");
    }

    @Test
    @DisplayName("방 입장 - 분산 락 획득 실패 시")
    void userJoin_lockAcquisitionFail() throws InterruptedException {
        // given
        JoinRoomRequest request = new JoinRoomRequest("room-123", "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn(null);
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        // when
        chatRoomService.userJoin(request);

        // then
        verify(messageBroadcaster).sendError("guestUser", "잠시 후 다시 시도해주세요.");
    }

    @Test
    @DisplayName("방 입장 - 방이 존재하지 않는 경우")
    void userJoin_roomNotFound() throws InterruptedException {
        // given
        JoinRoomRequest request = new JoinRoomRequest("room-123", "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn(null);
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(redisService.getChatRoom("room-123")).thenReturn(null);

        // when
        chatRoomService.userJoin(request);

        // then
        verify(messageBroadcaster).sendError("guestUser", "채팅방이 존재하지 않습니다.");
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("방 입장 - 방이 가득 차거나 이미 참여 중인 경우")
    void userJoin_roomFullOrAlreadyJoined() throws InterruptedException {
        // given
        JoinRoomRequest request = new JoinRoomRequest("room-123", "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn(null);
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        ChatRoom fullRoom = ChatRoom.builder()
                .roomId("room-123")
                .maxPlayers(1)
                .participants(new ArrayList<>(List.of(ChatUser.builder().userId("someHost").build())))
                .build();
        when(redisService.getChatRoom("room-123")).thenReturn(fullRoom);
        when(userService.getUserByLoginId("guestUser")).thenReturn(guest);

        // when
        chatRoomService.userJoin(request);

        // then
        verify(messageBroadcaster).sendError("guestUser", "채팅방이 가득 찼거나 이미 참여 중입니다.");
    }

    @Test
    @DisplayName("방 입장 - 성공")
    void userJoin_success() throws InterruptedException {
        // given
        JoinRoomRequest request = new JoinRoomRequest("room-123", "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn(null);
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        when(userService.getUserByLoginId("guestUser")).thenReturn(guest);

        // when
        chatRoomService.userJoin(request);

        // then
        assertThat(chatRoom.getParticipants()).hasSize(2);
        verify(redisService).saveChatRoom(chatRoom);
        verify(redisService).saveUserSession("guestUser", "room-123", null);
        verify(messageBroadcaster).broadcastToRoom(eq("room-123"), any(ChatMessage.class));
        verify(messageBroadcaster).notifyRoomListUpdated();
    }

    // ================== userLeave Tests ================== //

    @Test
    @DisplayName("방 퇴장 - 방 정보가 없는 경우")
    void userLeave_invalidRoom() {
        // given
        LeaveRoomRequest request = new LeaveRoomRequest(null, "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn(null);

        // when
        chatRoomService.userLeave(request);

        // then
        verify(messageBroadcaster).sendError("guestUser", "방 정보가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("방 퇴장 - 게임 진행 중으로 퇴장이 차단된 경우")
    void userLeave_blockedByGameInProgress() throws InterruptedException {
        // given
        LeaveRoomRequest request = new LeaveRoomRequest("room-123", "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        when(gameQueryService.canPlayerLeaveRoom("room-123", "guestUser")).thenReturn(false);

        // when
        chatRoomService.userLeave(request);

        // then
        verify(messageBroadcaster).sendError("guestUser", "게임이 진행 중입니다. 게임이 끝날 때까지 방을 나갈 수 없습니다.");
    }

    @Test
    @DisplayName("방 퇴장 - 방에 참여하고 있지 않은 유저 퇴장 시도 시 세션만 삭제")
    void userLeave_notParticipantOnlyDeletesSession() throws InterruptedException {
        // given
        LeaveRoomRequest request = new LeaveRoomRequest("room-123", "guestUser");
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(gameQueryService.canPlayerLeaveRoom("room-123", "guestUser")).thenReturn(true);
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom); // chatRoom에는 hostUser만 있음

        // when
        chatRoomService.userLeave(request);

        // then
        verify(redisService).deleteUserSession("guestUser");
        verify(redisService, never()).saveChatRoom(any());
    }

    @Test
    @DisplayName("방 퇴장 - 마지막 인원이 나가서 방이 삭제되는 경우")
    void userLeave_deleteRoomWhenEmpty() throws InterruptedException {
        // given
        LeaveRoomRequest request = new LeaveRoomRequest("room-123", "hostUser");
        when(redisService.getUserRoomId("hostUser")).thenReturn("room-123");
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(gameQueryService.canPlayerLeaveRoom("room-123", "hostUser")).thenReturn(true);
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom); // hostUser만 들어있는 방

        // when
        chatRoomService.userLeave(request);

        // then
        verify(redisService).deleteUserSession("hostUser");
        verify(redisService).deleteChatRoom("room-123");
        verify(stringRedisTemplate).delete("chat:logs:room-123");
        verify(messageBroadcaster).notifyRoomListUpdated();
    }

    @Test
    @DisplayName("방 퇴장 - 방장 퇴장으로 다른 사람에게 위임 및 방 정보 업데이트")
    void userLeave_delegateHost() throws InterruptedException {
        // given
        chatRoom.addParticipant(ChatUser.builder()
                .userId(guest.getUserLoginId())
                .userName(guest.getNickname())
                .isHost(false)
                .build()); // hostUser, guestUser가 있는 상태

        LeaveRoomRequest request = new LeaveRoomRequest("room-123", "hostUser");
        when(redisService.getUserRoomId("hostUser")).thenReturn("room-123");
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(gameQueryService.canPlayerLeaveRoom("room-123", "hostUser")).thenReturn(true);
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);

        // when
        chatRoomService.userLeave(request);

        // then
        assertThat(chatRoom.getHostId()).isEqualTo("guestUser");
        assertThat(chatRoom.getParticipants().get(0).isHost()).isTrue();
        verify(redisService).saveChatRoom(chatRoom);
        verify(messageBroadcaster).broadcastToRoom(eq("room-123"), any(ChatMessage.class));
        verify(messageBroadcaster).sendHostChanged("room-123", "guestUser", guest.getNickname());
        verify(messageBroadcaster).notifyRoomListUpdated();
    }

    // ================== processAndBroadcastMessage Tests ================== //

    @Test
    @DisplayName("메시지 브로드캐스트 - 페이로드 null")
    void processAndBroadcastMessage_payloadNull() {
        chatRoomService.processAndBroadcastMessage(null, "someUser");
        verify(messageBroadcaster).sendError("someUser", "메시지 형식이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("메시지 브로드캐스트 - 메시지 검증 실패(null 내용)")
    void processAndBroadcastMessage_contentNull() {
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .content(null)
                .build();
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);

        // guest가 chatRoom에 참여 중으로 추가해 줌
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());

        chatRoomService.processAndBroadcastMessage(message, "guestUser");
        verify(messageBroadcaster).sendError("guestUser", "메시지를 입력해주세요.");
    }

    @Test
    @DisplayName("메시지 브로드캐스트 - 채팅 권한이 없는 경우")
    void processAndBroadcastMessage_noChatPermission() {
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .content("안녕하세요")
                .build();
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());
        when(userService.getUserByLoginId("guestUser")).thenReturn(guest);
        when(gameQueryService.canPlayerChat("room-123", "guestUser")).thenReturn(false);

        chatRoomService.processAndBroadcastMessage(message, "guestUser");

        verify(messageBroadcaster).sendError("guestUser", "지금은 채팅을 할 수 없습니다.");
    }

    @Test
    @DisplayName("메시지 브로드캐스트 - 밤 페이즈 마피아 채팅 개별 전송")
    void processAndBroadcastMessage_mafiaNightChat() {
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .content("밤인데 마피아 누구 쏠까요?")
                .build();
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());
        when(userService.getUserByLoginId("guestUser")).thenReturn(guest);
        when(gameQueryService.canPlayerChat("room-123", "guestUser")).thenReturn(true);

        Game game = new Game();
        game.setGameId("game-123");
        when(gameQueryService.getGameByRoomId("room-123")).thenReturn(game);

        GamePlayerState hostPlayer = GamePlayerState.builder()
                .playerId("hostUser")
                .role(PlayerRole.MAFIA)
                .isAlive(true)
                .build();
        GamePlayerState guestPlayer = GamePlayerState.builder()
                .playerId("guestUser")
                .role(PlayerRole.MAFIA)
                .isAlive(true)
                .build();
        GamePlayerState otherPlayer = GamePlayerState.builder()
                .playerId("otherUser")
                .role(PlayerRole.CITIZEN)
                .isAlive(true)
                .build();

        GameState gameState = GameState.builder()
                .gameId("game-123")
                .gamePhase(GamePhase.NIGHT_ACTION)
                .players(List.of(hostPlayer, guestPlayer, otherPlayer))
                .build();
        when(gameQueryService.getGameState("game-123")).thenReturn(gameState);

        // when
        chatRoomService.processAndBroadcastMessage(message, "guestUser");

        // then
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageBroadcaster, times(2)).sendPrivateMessage(anyString(), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(MessageType.MAFIA_CHAT);
        verify(messageBroadcaster, never()).broadcastToRoom(anyString(), any());
    }

    @Test
    @DisplayName("메시지 브로드캐스트 - 10개 쌓였을 때 Redis Flush 및 AI suggestion 비동기 트리거")
    void processAndBroadcastMessage_bufferFlushAndAiTrigger() {
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());
        when(userService.getUserByLoginId("guestUser")).thenReturn(guest);
        when(gameQueryService.canPlayerChat("room-123", "guestUser")).thenReturn(true);
        when(gameQueryService.getGameByRoomId("room-123")).thenReturn(null); // 일반 채팅
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        GameState gameState = GameState.builder()
                .gameId("game-123")
                .gamePhase(GamePhase.DAY_DISCUSSION)
                .build();
        when(gameStateRepository.findByRoomId("room-123")).thenReturn(Optional.of(gameState));

        // 10번 메시지 전송
        for (int i = 1; i <= 10; i++) {
            ChatMessage message = ChatMessage.builder()
                    .roomId("room-123")
                    .content("메시지 " + i)
                    .build();
            chatRoomService.processAndBroadcastMessage(message, "guestUser");
        }

        // then
        verify(messageBroadcaster, times(10)).broadcastToRoom(eq("room-123"), any(ChatMessage.class));
        verify(stringRedisTemplate).delete("chat:logs:room-123");
        verify(listOperations).rightPushAll(eq("chat:logs:room-123"), anyList());
        verify(suggestionService).generateAiSuggestionsAsync("game-123", GamePhase.DAY_DISCUSSION);
    }

    // ================== processAndPrivateMessage Tests ================== //

    @Test
    @DisplayName("개인 메시지 전송 - 대상 미지정")
    void processAndPrivateMessage_noRecipient() {
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .recipient(null)
                .content("안녕")
                .build();
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());

        chatRoomService.processAndPrivateMessage(message, "guestUser");

        verify(messageBroadcaster).sendError("guestUser", "메시지를 보낼 대상을 지정해주세요.");
    }

    @Test
    @DisplayName("개인 메시지 전송 - 자기 자신에게 보낼 때")
    void processAndPrivateMessage_sendToSelf() {
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .recipient("guestUser")
                .content("안녕")
                .build();
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());

        chatRoomService.processAndPrivateMessage(message, "guestUser");

        verify(messageBroadcaster).sendError("guestUser", "자기 자신에게는 메시지를 보낼 수 없습니다.");
    }

    @Test
    @DisplayName("개인 메시지 전송 - 다른 방 유저에게 보낼 때")
    void processAndPrivateMessage_recipientNotInRoom() {
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .recipient("otherUser")
                .content("안녕")
                .build();
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());

        chatRoomService.processAndPrivateMessage(message, "guestUser");

        verify(messageBroadcaster).sendError("guestUser", "같은 방에 있는 사용자에게만 메시지를 보낼 수 있습니다.");
    }

    @Test
    @DisplayName("개인 메시지 전송 - 성공")
    void processAndPrivateMessage_success() {
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .recipient("hostUser")
                .content("비밀 메시지")
                .build();
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        chatRoom.addParticipant(ChatUser.builder().userId("guestUser").build());
        when(gameQueryService.canPlayerChat("room-123", "guestUser")).thenReturn(true);
        when(userService.getUserByLoginId("guestUser")).thenReturn(guest);

        chatRoomService.processAndPrivateMessage(message, "guestUser");

        verify(messageBroadcaster).sendPrivateMessage(eq("hostUser"), any(ChatMessage.class));
    }

    // ================== handleDisconnect Tests ================== //

    @Test
    @DisplayName("연결 종료 처리 - 게임 진행 중으로 재연결 대기")
    void handleDisconnect_gameInProgress() {
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        when(gameQueryService.canPlayerLeaveRoom("room-123", "guestUser")).thenReturn(false);

        chatRoomService.handleDisconnect("guestUser");

        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("연결 종료 처리 - 게임 진행 중이 아니어서 퇴장 처리")
    void handleDisconnect_leaveRoom() throws InterruptedException {
        when(redisService.getUserRoomId("guestUser")).thenReturn("room-123");
        when(redisService.getChatRoom("room-123")).thenReturn(chatRoom);
        when(gameQueryService.canPlayerLeaveRoom("room-123", "guestUser")).thenReturn(true);
        when(redissonClient.getLock("lock:room:room-123")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        chatRoomService.handleDisconnect("guestUser");

        verify(rLock).unlock();
    }
}
