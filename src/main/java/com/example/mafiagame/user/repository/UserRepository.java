package com.example.mafiagame.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.mafiagame.user.domain.AuthProvider;
import com.example.mafiagame.user.domain.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserLoginId(String userLoginId);

    // N+1 해결: IN 쿼리로 여러 유저 한 번에 조회
    List<User> findAllByUserLoginIdIn(List<String> userLoginIds);

    // OAuth2: provider와 providerId로 사용자 조회
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
