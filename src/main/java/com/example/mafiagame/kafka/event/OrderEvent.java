package com.example.mafiagame.kafka.event;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kafka를 통해 전달되는 주문 관련 이벤트.
 *
 * <p>주문 생성, 완료, 취소, 환불 등의 상태 변경 시 발행된다.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String orderId;
    private String userId;
    private EventType eventType;
    private Integer totalAmount;
    private List<OrderItemInfo> items;

    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();

    /**
     * 주문 이벤트 유형.
     */
    public enum EventType {
        ORDER_CREATED,
        ORDER_COMPLETED,
        ORDER_CANCELLED,
        ORDER_REFUNDED
    }

    /**
     * 이벤트 내 주문 아이템 정보.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long itemId;
        private String itemName;
        private Integer quantity;
        private Integer unitPrice;
    }
}
