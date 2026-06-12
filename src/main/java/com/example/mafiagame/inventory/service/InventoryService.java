package com.example.mafiagame.inventory.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.mafiagame.inventory.domain.Item;
import com.example.mafiagame.inventory.domain.UserInventory;
import com.example.mafiagame.inventory.repository.ItemRepository;
import com.example.mafiagame.inventory.repository.UserInventoryRepository;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 인벤토리 관리 서비스.
 *
 * <p>아이템 지급, 회수, 조회를 담당한다. Kafka Consumer에서 호출된다.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final UserInventoryRepository userInventoryRepository;
    private final ItemRepository itemRepository;
    private final UsersRepository usersRepository;

    /**
     * 사용자에게 아이템을 지급한다.
     *
     * @param userId   사용자 로그인 ID
     * @param itemId   지급할 아이템 ID
     * @param quantity 수량
     */
    @Transactional
    public void grantItem(String userId, Long itemId, int quantity) {
        Users user = usersRepository.findByUserLoginId(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("아이템을 찾을 수 없습니다: " + itemId));

        LocalDateTime expiresAt = null;
        if (item.getDurationDays() != null) {
            expiresAt = LocalDateTime.now().plusDays(item.getDurationDays());
        }

        UserInventory inventory = UserInventory.builder()
                .user(user)
                .item(item)
                .quantity(quantity)
                .acquiredAt(LocalDateTime.now())
                .expiresAt(expiresAt)
                .active(true)
                .build();

        userInventoryRepository.save(inventory);
        log.info("[아이템 지급] userId={}, itemId={}, quantity={}", userId, itemId, quantity);
    }

    /**
     * 사용자의 특정 아이템을 회수(비활성화)한다.
     *
     * @param userId 사용자 DB ID
     * @param itemId 회수할 아이템 ID
     */
    @Transactional
    public void revokeItem(Long userId, Long itemId) {
        List<UserInventory> inventories =
                userInventoryRepository.findByUser_UserIdAndItem_ItemIdAndActiveTrue(userId, itemId);

        inventories.forEach(inv -> inv.setActive(false));
        userInventoryRepository.saveAll(inventories);
        log.info("[아이템 회수] userId={}, itemId={}, count={}", userId, itemId, inventories.size());
    }

    /**
     * 사용자의 활성 인벤토리 조회.
     *
     * @param userId 사용자 DB ID
     * @return 활성 인벤토리 리스트
     */
    public List<UserInventory> getUserInventory(Long userId) {
        return userInventoryRepository.findByUser_UserIdAndActiveTrue(userId);
    }

    /**
     * 만료된 아이템 일괄 비활성화 (Batch Job에서 호출).
     *
     * @return 비활성화된 레코드 수
     */
    @Transactional
    public int deactivateExpiredItems() {
        int count = userInventoryRepository.deactivateExpiredItems(LocalDateTime.now());
        log.info("[만료 아이템 정리] 비활성화 건수={}", count);
        return count;
    }
}
