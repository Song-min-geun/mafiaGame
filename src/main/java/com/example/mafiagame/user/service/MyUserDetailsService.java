package com.example.mafiagame.user.service;

import java.util.Collections;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MyUserDetailsService implements UserDetailsService {

    private final UsersRepository usersRepository;

    @Override
    @Cacheable(value = "userDetails", key = "#userLoginId", unless = "#result == null")
    public UserDetails loadUserByUsername(String userLoginId) throws UsernameNotFoundException {
        Users users = usersRepository.findByUserLoginId(userLoginId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with loginId: " + userLoginId));

        String role = users.getUserRole().getRoleName();

        return CustomUserDetails.builder()
                .username(users.getUserLoginId())
                .password(users.getUserLoginPassword())
                .roles(Collections.singletonList(role))
                .build();
    }
}
