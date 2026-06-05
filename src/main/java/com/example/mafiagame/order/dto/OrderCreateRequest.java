package com.example.mafiagame.order.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 주문 생성 요청 DTO.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateRequest {

    @NotEmpty(message = "주문 아이템은 최소 1개 이상이어야 합니다.")
    @Valid
    private List<ItemRequest> items;

    /**
     * 개별 아이템 주문 정보.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemRequest {

        @NotNull(message = "아이템 ID는 필수입니다.")
        private Long itemId;

        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
        private int quantity = 1;
    }
}
