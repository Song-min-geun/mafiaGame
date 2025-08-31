package com.example.mafiagame.user.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.mafiagame.global.dto.CommonResponse;
import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.dto.reponse.UserDetailForAdmin;
import com.example.mafiagame.user.dto.reponse.UserDetailForUser;
import com.example.mafiagame.user.dto.request.LoginRequest;
import com.example.mafiagame.user.dto.request.RegistRequest;
import com.example.mafiagame.user.dto.request.UpdatePasswordRequest;
import com.example.mafiagame.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/register")
    public ResponseEntity<CommonResponse<Void>> registerUser(@Valid @RequestBody RegistRequest requestDto) {
        userService.registerUser(requestDto);

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null, "회원가입에 성공했습니다."));
    }


    @PostMapping("/login")
    public ResponseEntity<CommonResponse<Map<String, String>>> login(@Valid @RequestBody LoginRequest requestDto) {
        String token = userService.login(requestDto);

        Map<String, String> tokenMap = Map.of("token", token);

        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(tokenMap, "로그인에 성공했습니다."));
    }


    // 현재 로그인한 사용자 정보 조회
    @GetMapping("/me")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getCurrentUser(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(CommonResponse.failure("로그인이 필요합니다."));
        }
        
        String userLoginId = authentication.getName();
        User user = userService.getUserByLoginId(userLoginId);
        
        Map<String, Object> userInfo = Map.of(
            "userId", user.getUserId(),
            "userLoginId", user.getUserLoginId(),
            "nickname", user.getNickname()
        );
        
        return ResponseEntity.ok(CommonResponse.success(userInfo, "현재 사용자 정보 조회 성공"));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserDetails(@PathVariable Long userId, Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals("ROLE_ADMIN"));

        if (isAdmin) {
            // 관리자일 경우, 관리자용 상세 정보를 조회하여 반환
            UserDetailForAdmin adminResponse = userService.getUserDetailForAdmin(userId);
            return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(adminResponse, "관리자용 사용자 정보 조회 성공"));
        } else {
            // 일반 사용자일 경우, 공개용 프로필 정보를 조회하여 반환
            UserDetailForUser publicResponse = userService.getUserDetailForUser(userId);
            return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(publicResponse, "사용자 프로필 조회 성공"));
        }
    }

    @PutMapping("/password")
    public ResponseEntity<CommonResponse<Void>> updateMyPassword(Authentication authentication, @Valid @RequestBody UpdatePasswordRequest requestDto) {

        String userLoginId = authentication.getName();

        userService.updatePassword(
            userLoginId,
            requestDto.currentPassword(),
            requestDto.newPassword()
        );

        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null, "비밀번호가 성공적으로 변경되었습니다."));
    }
}
