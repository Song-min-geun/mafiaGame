package com.example.mafiagame.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
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
