package com.example.mafiagame.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.mafiagame.user.domain.AuthProvider;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.dto.reponse.Top10UserResponse;

@Repository
public interface UsersRepository extends JpaRepository<Users, Long> {
    Optional<Users> findByUserLoginId(String userLoginId);

    Optional<Users> findByProviderAndProviderId(AuthProvider provider, String providerId);

    List<Users> findAllByUserLoginIdIn(List<String> userLoginIds);

    @Query("SELECT new com.example.mafiagame.user.dto.reponse.Top10UserResponse(" +
            "u.nickname, u.winRate, u.playCount) " +
            "FROM Users u WHERE u.playCount > 50 " +
            "ORDER BY u.winRate DESC, u.playCount DESC")
    List<Top10UserResponse> findTopRanking(Pageable pageable);

    /**
     * 원자적 전적 업데이트 (단일 UPDATE 쿼리로 동시성 안전)
     * - SELECT 없이 DB 레벨에서 playCount++, winCount++ 처리
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Users u SET " +
            "u.playCount = u.playCount + 1, " +
            "u.winCount = u.winCount + CASE WHEN :isWin = true THEN 1 ELSE 0 END, " +
            "u.winRate = (u.winCount + CASE WHEN :isWin = true THEN 1.0 ELSE 0.0 END) / (u.playCount + 1.0) " +
            "WHERE u.userLoginId = :playerId")
    int updateStats(@Param("playerId") String playerId, @Param("isWin") boolean isWin);
}
