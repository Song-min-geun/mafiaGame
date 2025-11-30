package com.example.mafiagame.user.service;

import java.util.Collections;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MyUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String userLoginId) throws UsernameNotFoundException {
        User user = userRepository.findByUserLoginId(userLoginId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with loginId: " + userLoginId));

        // ❗ 수정된 부분: 사용자의 Role을 GrantedAuthority로 변환합니다.
        // UserRole enum의 getRoleName() 메서드를 사용하여 "ROLE_USER" 형태로 변환
        String role = user.getUserRole().getRoleName();
        List<GrantedAuthority> authorities = Collections.singletonList(new SimpleGrantedAuthority(role));

        // Spring Security의 UserDetails 객체로 변환하여 반환
        return new org.springframework.security.core.userdetails.User(
                user.getUserLoginId(),
                user.getUserLoginPassword(),
                authorities
        );
    }
}
