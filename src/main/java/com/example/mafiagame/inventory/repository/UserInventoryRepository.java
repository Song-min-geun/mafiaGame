package com.example.mafiagame.inventory.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.mafiagame.inventory.domain.UserInventory;

/**
 * 사용자 인벤토리 JPA 리포지토리.
 */
public interface UserInventoryRepository extends JpaRepository<UserInventory, Long> {

    /**
     * 특정 사용자의 활성 인벤토리 조회.
     */
    List<UserInventory> findByUser_UserIdAndActiveTrue(Long userId);

    /**
     * 특정 사용자의 특정 아이템 활성 인벤토리 조회.
     */
    List<UserInventory> findByUser_UserIdAndItem_ItemIdAndActiveTrue(Long userId, Long itemId);

    /**
     * 만료된 아이템 일괄 비활성화 (Spring Batch에서 사용).
     *
     * @param now 현재 시각
     * @return 비활성화된 레코드 수
     */
    @Modifying
    @Query("UPDATE UserInventory ui SET ui.active = false WHERE ui.expiresAt <= :now AND ui.active = true")
    int deactivateExpiredItems(@Param("now") LocalDateTime now);
}
