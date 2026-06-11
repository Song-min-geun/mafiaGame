package com.example.mafiagame.inventory.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.mafiagame.inventory.domain.Item;
import com.example.mafiagame.inventory.domain.ItemCategory;
import com.example.mafiagame.inventory.domain.UserInventory;
import com.example.mafiagame.inventory.service.InventoryService;
import com.example.mafiagame.inventory.service.ItemService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 아이템 카탈로그 및 사용자 인벤토리 REST API.
 */
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
@Tag(name = "아이템/인벤토리", description = "아이템 조회 및 사용자 인벤토리 관리 API")
public class ItemController {

    private final ItemService itemService;
    private final InventoryService inventoryService;

    /**
     * 판매 중인 전체 아이템 목록 조회.
     */
    @GetMapping
    @Operation(summary = "전체 아이템 목록", description = "활성화된 판매 아이템 전체를 조회합니다.")
    public ResponseEntity<List<Item>> getAllItems() {
        return ResponseEntity.ok(itemService.getActiveItems());
    }

    /**
     * 카테고리별 아이템 조회.
     */
    @GetMapping("/category/{category}")
    @Operation(summary = "카테고리별 아이템", description = "COSMETIC, BOOST, SEASON_PASS, COIN 중 하나를 지정합니다.")
    public ResponseEntity<List<Item>> getItemsByCategory(@PathVariable ItemCategory category) {
        return ResponseEntity.ok(itemService.getItemsByCategory(category));
    }

    /**
     * 아이템 상세 조회.
     */
    @GetMapping("/{itemId}")
    @Operation(summary = "아이템 상세", description = "특정 아이템의 상세 정보를 조회합니다.")
    public ResponseEntity<Item> getItem(@PathVariable Long itemId) {
        return ResponseEntity.ok(itemService.getItem(itemId));
    }

    /**
     * 사용자 인벤토리 조회.
     */
    @GetMapping("/inventory/{userId}")
    @Operation(summary = "사용자 인벤토리", description = "특정 사용자가 보유 중인 활성 아이템 목록을 조회합니다.")
    public ResponseEntity<List<UserInventory>> getUserInventory(@PathVariable Long userId) {
        return ResponseEntity.ok(inventoryService.getUserInventory(userId));
    }
}
