package com.example.mafiagame.kafka.event;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kafka를 통해 전달되는 결제 관련 이벤트.
 *
 * <p>PG사 결제 승인/실패/취소 시 발행되어 주문 상태 변경 및 정산에 활용된다.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String paymentId;
    private String orderId;
    private String userId;
    private EventType eventType;
    private Integer amount;
    private String paymentMethod;
    private String pgPaymentKey;

    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();

    /**
     * 결제 이벤트 유형.
     */
    public enum EventType {
        PAYMENT_COMPLETED,
        PAYMENT_FAILED,
        PAYMENT_CANCELLED
    }
}
