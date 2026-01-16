package com.example.mafiagame.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.example.mafiagame.user.domain.AuthProvider;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.dto.reponse.Top10UserResponse;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserLoginId(String userLoginId);

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    List<User> findAllByUserLoginIdIn(List<String> userLoginIds);

    @Query("SELECT new com.example.mafiagame.user.dto.reponse.Top10UserResponse(" +
            "u.nickname, u.winRate, u.playCount) " +
            "FROM User u WHERE u.playCount > 50 " +
            "ORDER BY u.winRate DESC, u.playCount DESC")
    List<Top10UserResponse> findTopRanking(Pageable pageable);
}
