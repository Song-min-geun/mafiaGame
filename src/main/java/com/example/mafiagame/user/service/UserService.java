package com.example.mafiagame.user.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.mafiagame.global.error.CommonException;
import com.example.mafiagame.global.error.ErrorCode;
import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.global.jwt.RefreshTokenService;
import com.example.mafiagame.user.domain.Users;
import static com.example.mafiagame.user.domain.UserRole.USER;

import java.util.List;

import com.example.mafiagame.user.dto.reponse.TokenResponse;
import com.example.mafiagame.user.dto.reponse.Top10UserResponse;
import com.example.mafiagame.user.dto.reponse.UserDetailForAdmin;
import com.example.mafiagame.user.dto.reponse.UserDetailForUser;
import com.example.mafiagame.user.dto.request.LoginRequest;
import com.example.mafiagame.user.dto.request.RegistRequest;
import com.example.mafiagame.user.repository.UsersRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UsersRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    // 유저 회원가입
    public void registerUser(RegistRequest request) {
        // 중복 사용자명 확인
        if (userRepository.findByUserLoginId(request.userLoginId()).isPresent()) {
            throw new CommonException(ErrorCode.USER_ALREADY_EXISTS);
        }

        Users users = request.toEntity();
        userRepository.save(users);
    }

    // 유저 로그인 - Access + Refresh Token 반환
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.userLoginId(), request.userLoginPassword()));
        } catch (BadCredentialsException e) {
            throw new RuntimeException("아이디 또는 비밀번호가 일치하지 않습니다.");
        }

        String accessToken = jwtUtil.generateAccessToken(request.userLoginId());
        String refreshToken = jwtUtil.generateRefreshToken(request.userLoginId());

        // Refresh Token을 Redis에 저장
        refreshTokenService.saveRefreshToken(request.userLoginId(), refreshToken);

        return new TokenResponse(accessToken, refreshToken);
    }

    // 유저 상세정보 확인 ( 유저용 )
    @Transactional(readOnly = true)
    public UserDetailForUser getUserDetailForUser(Long userId) {
        Users users = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("해당 ID의 사용자를 찾을 수 없습니다: " + userId));
        return new UserDetailForUser(users.getNickname());
    }

    // 유저 상세정보 ( 관리자용 )
    @Transactional(readOnly = true)
    public UserDetailForAdmin getUserDetailForAdmin(Long userId) {
        Users users = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("해당 ID의 사용자를 찾을 수 없습니다: " + userId));
        return new UserDetailForAdmin(users);
    }

    // userLoginId로 사용자 조회
    @Transactional(readOnly = true)
    public Users getUserByLoginId(String userLoginId) {
        return userRepository.findByUserLoginId(userLoginId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userLoginId));
    }

    @Transactional
    @CacheEvict(value = "userDetails", key = "#userLoginId")
    public void updatePassword(String userLoginId, String currentPassword, String newPassword) {
        Users users = getUserByLoginId(userLoginId);

        if (!passwordEncoder.matches(currentPassword, users.getUserLoginPassword())) {
            throw new RuntimeException("현재 비밀번호가 일치하지 않습니다.");
        }

        String encodedNewPassword = passwordEncoder.encode(newPassword);
        users.updateUserLoginPassword(encodedNewPassword);
    }

    @Transactional
    public void createDummyUsers(int count) {
        for (int i = 1; i <= count; i++) {
            String dummyId = "dummy" + i;
            if (userRepository.findByUserLoginId(dummyId).isEmpty()) {
                Users users = Users.builder()
                        .userLoginId(dummyId)
                        .userLoginPassword(passwordEncoder.encode("password1234!"))
                        .nickname("dummy" + i)
                        .userRole(USER)
                        .build();
                userRepository.save(users);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Top10UserResponse> getTopRanking() {
        return userRepository.findTopRanking(PageRequest.of(0, 10));
    }
}
