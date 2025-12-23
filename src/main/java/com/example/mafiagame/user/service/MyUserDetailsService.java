package com.example.mafiagame.user.service;

import java.util.Collections;

import org.springframework.cache.annotation.Cacheable;
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
    @Cacheable(value = "userDetails", key = "#userLoginId", unless = "#result == null")
    public UserDetails loadUserByUsername(String userLoginId) throws UsernameNotFoundException {
        User user = userRepository.findByUserLoginId(userLoginId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with loginId: " + userLoginId));

        String role = user.getUserRole().getRoleName();

        return CustomUserDetails.builder()
                .username(user.getUserLoginId())
                .password(user.getUserLoginPassword())
                .roles(Collections.singletonList(role))
                .build();
    }
}
