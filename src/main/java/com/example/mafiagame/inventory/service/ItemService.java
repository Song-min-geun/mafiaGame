package com.example.mafiagame.inventory.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.mafiagame.inventory.domain.Item;
import com.example.mafiagame.inventory.domain.ItemCategory;
import com.example.mafiagame.inventory.repository.ItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아이템 카탈로그 관리 서비스.
 *
 * <p>아이템의 CRUD 및 카테고리별 조회를 담당한다.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemService {

    private final ItemRepository itemRepository;

    /**
     * 활성화된 전체 아이템 목록 조회.
     *
     * @return 활성 아이템 리스트
     */
    public List<Item> getActiveItems() {
        return itemRepository.findByActiveTrue();
    }

    /**
     * 카테고리별 활성 아이템 조회.
     *
     * @param category 아이템 카테고리
     * @return 해당 카테고리의 활성 아이템 리스트
     */
    public List<Item> getItemsByCategory(ItemCategory category) {
        return itemRepository.findByCategoryAndActiveTrue(category);
    }

    /**
     * 아이템 단건 조회.
     *
     * @param itemId 아이템 ID
     * @return 아이템 엔티티
     * @throws IllegalArgumentException 아이템을 찾을 수 없는 경우
     */
    public Item getItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("아이템을 찾을 수 없습니다: " + itemId));
    }

    /**
     * 새 아이템 등록.
     *
     * @param item 등록할 아이템
     * @return 저장된 아이템
     */
    @Transactional
    public Item createItem(Item item) {
        Item saved = itemRepository.save(item);
        log.info("[아이템 등록] itemId={}, name={}, category={}", saved.getItemId(), saved.getItemName(), saved.getCategory());
        return saved;
    }

    /**
     * 아이템 비활성화 (소프트 삭제).
     *
     * @param itemId 비활성화할 아이템 ID
     */
    @Transactional
    public void deactivateItem(Long itemId) {
        Item item = getItem(itemId);
        item.setActive(false);
        itemRepository.save(item);
        log.info("[아이템 비활성화] itemId={}", itemId);
    }
}
