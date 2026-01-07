package com.example.mafiagame.global.oauth2;

import com.example.mafiagame.user.domain.AuthProvider;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.domain.UserRole;
import com.example.mafiagame.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase());

        OAuth2UserInfo userInfo = extractUserInfo(registrationId, oAuth2User.getAttributes());

        User user = saveOrUpdate(provider, userInfo);

        log.info("OAuth2 로그인 성공: provider={}, email={}", provider, userInfo.getEmail());

        return new CustomOAuth2User(user, oAuth2User.getAttributes());
    }

    private OAuth2UserInfo extractUserInfo(String registrationId, Map<String, Object> attributes) {
        return switch (registrationId.toLowerCase()) {
            case "google" -> new GoogleUserInfo(attributes);
            case "github" -> new GitHubUserInfo(attributes);
            case "naver" -> new NaverUserInfo(attributes);
            case "kakao" -> new KakaoUserInfo(attributes);
            default -> throw new OAuth2AuthenticationException("지원하지 않는 OAuth2 제공자: " + registrationId);
        };
    }

    private User saveOrUpdate(AuthProvider provider, OAuth2UserInfo userInfo) {
        Optional<User> existingUser = userRepository.findByProviderAndProviderId(provider, userInfo.getId());

        if (existingUser.isPresent()) {
            // 기존 사용자 정보 업데이트
            User user = existingUser.get();
            user.setNickname(userInfo.getName());
            return userRepository.save(user);
        }

        // 새 사용자 생성
        String userLoginId = provider.name().toLowerCase() + "_" + userInfo.getId();
        User newUser = User.builder()
                .userLoginId(userLoginId)
                .nickname(userInfo.getName())
                .userRole(UserRole.USER)
                .provider(provider)
                .providerId(userInfo.getId())
                .build();

        return userRepository.save(newUser);
    }
}
