package com.example.mafiagame.global.oauth2;

import com.example.mafiagame.user.domain.Users;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@Getter
public class CustomOAuth2User implements OAuth2User {

    private final Users users;
    private final Map<String, Object> attributes;

    public CustomOAuth2User(Users users, Map<String, Object> attributes) {
        this.users = users;
        this.attributes = attributes;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + users.getUserRole().name()));
    }

    @Override
    public String getName() {
        return users.getUserLoginId();
    }
}
