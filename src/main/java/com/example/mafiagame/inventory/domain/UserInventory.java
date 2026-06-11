package com.example.mafiagame.inventory.domain;

import java.time.LocalDateTime;

import com.example.mafiagame.user.domain.Users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 사용자가 보유한 아이템 인벤토리.
 *
 * <p>아이템 지급/회수/만료 관리를 담당하는 엔티티.</p>
 */
@Entity
@Table(name = "user_inventory", indexes = {
        @Index(name = "idx_user_inventory_user", columnList = "user_id"),
        @Index(name = "idx_user_inventory_expires", columnList = "expiresAt")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    /** 보유 수량 (코인, 소비형 아이템 등). */
    @Builder.Default
    @Column(nullable = false)
    private Integer quantity = 1;

    /** 획득 시각. */
    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime acquiredAt = LocalDateTime.now();

    /** 만료 시각. null이면 영구 보유. */
    private LocalDateTime expiresAt;

    /** 활성화 여부. 만료 또는 회수 시 false. */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /**
     * 아이템이 만료되었는지 확인한다.
     *
     * @return 만료 여부
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
