package com.example.mafiagame.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.mafiagame.user.domain.AuthProvider;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.dto.reponse.Top10UserResponse;

import jakarta.persistence.LockModeType;

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
     * Pessimistic Lock (비관적 락) 적용된 조회
     * SELECT ... FOR UPDATE 쿼리 실행
     * 
     * InnoDB 분석 포인트:
     * - PK 동등 조건(=) 검색이므로 Record Lock만 발생 (Gap Lock 없음)
     * - 다른 트랜잭션은 이 행에 대해 쓰기/락 획득 불가 (읽기는 MVCC로 가능)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM Users u WHERE u.userId = :userId")
    Optional<Users> findByIdWithPessimisticLock(@Param("userId") Long userId);
}
