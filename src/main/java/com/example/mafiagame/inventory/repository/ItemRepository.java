package com.example.mafiagame.inventory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.mafiagame.inventory.domain.Item;
import com.example.mafiagame.inventory.domain.ItemCategory;

/**
 * 아이템 카탈로그 JPA 리포지토리.
 */
public interface ItemRepository extends JpaRepository<Item, Long> {

    /**
     * 활성화된 아이템 전체 조회.
     */
    List<Item> findByActiveTrue();

    /**
     * 카테고리별 활성 아이템 조회.
     */
    List<Item> findByCategoryAndActiveTrue(ItemCategory category);

    /**
     * 아이템명으로 조회.
     */
    Optional<Item> findByItemName(String itemName);
}
