package com.example.mafiagame.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
// import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_ranking", columnList = "winRate DESC, playCount DESC")
})
@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Users {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Column(unique = true, nullable = false)
    private String userLoginId;

    @Column(nullable = true)
    private String userLoginPassword;

    @Column(nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole userRole;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private AuthProvider provider = AuthProvider.LOCAL;

    @Column(nullable = true)
    private String providerId;

    @Builder.Default
    @Column(nullable = false)
    private int winCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private int playCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Double winRate = 0.0;

    /**
     * Optimistic Lock (낙관적 락)용 버전 필드
     * UPDATE 시 WHERE version = X 조건 자동 추w가
     * 버전 불일치 시 ObjectOptimisticLockingFailureException 발생
     */
    // @Version
    // private Long version;

    public void updateUserLoginPassword(String userLoginPassword) {
        this.userLoginPassword = userLoginPassword;
    }

    public void updateWinRate() {
        if (this.playCount > 0) {
            this.winRate = (double) this.winCount / this.playCount;
        }
    }

    public void incrementWinCount() {
        this.winCount++;
    }

    public void incrementPlayCount() {
        this.playCount++;
    }
}
