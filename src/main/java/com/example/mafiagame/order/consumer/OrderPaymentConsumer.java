package com.example.mafiagame.order.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.example.mafiagame.inventory.service.InventoryService;
import com.example.mafiagame.kafka.config.KafkaTopics;
import com.example.mafiagame.kafka.event.OrderEvent;
import com.example.mafiagame.kafka.event.PaymentEvent;
import com.example.mafiagame.order.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주문/결제 관련 Kafka Consumer.
 *
 * <p>
 * 결제 완료 → 주문 PAID → 주문 COMPLETED → 아이템 지급 플로우를 처리한다.
 * </p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrderPaymentConsumer {

    private final OrderService orderService;
    private final InventoryService inventoryService;

    /**
     * 결제 완료 이벤트 처리.
     *
     * <p>
     * 결제가 완료되면 주문 상태를 PAID → COMPLETED로 변경한다.
     * </p>
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "order-service-group")
    public void handlePaymentCompleted(ConsumerRecord<String, PaymentEvent> record, Acknowledgment ack) {
        PaymentEvent event = record.value();
        log.info("[Consumer] 결제 완료 수신: orderId={}", event.getOrderId());

        try {
            orderService.markAsPaid(event.getOrderId());
            orderService.completeOrder(event.getOrderId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[Consumer] 결제 완료 처리 실패: orderId={}", event.getOrderId(), e);
            // 재시도 후 DLT로 전달 (ErrorHandler에 의해 관리)
            throw e;
        }
    }

    /**
     * 주문 완료 이벤트 처리 → 아이템 지급.
     *
     * <p>
     * 주문이 COMPLETED 상태가 되면 사용자에게 아이템을 지급한다.
     * </p>
     */
    @KafkaListener(topics = KafkaTopics.ORDER_COMPLETED, groupId = "inventory-service-group")
    public void handleOrderCompleted(ConsumerRecord<String, OrderEvent> record, Acknowledgment ack) {
        OrderEvent event = record.value();
        log.info("[Consumer] 주문 완료 수신 → 아이템 지급: orderId={}, userId={}", event.getOrderId(), event.getUserId());

        try {
            for (OrderEvent.OrderItemInfo item : event.getItems()) {
                inventoryService.grantItem(event.getUserId(), item.getItemId(), item.getQuantity());
            }
            ack.acknowledge();
            log.info("[Consumer] 아이템 지급 완료: orderId={}", event.getOrderId());
        } catch (Exception e) {
            log.error("[Consumer] 아이템 지급 실패: orderId={}", event.getOrderId(), e);
            throw e;
        }
    }

    /**
     * 결제 실패 이벤트 처리 → 주문 취소.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "order-service-group")
    public void handlePaymentFailed(ConsumerRecord<String, PaymentEvent> record, Acknowledgment ack) {
        PaymentEvent event = record.value();
        log.warn("[Consumer] 결제 실패 수신 → 주문 취소: orderId={}", event.getOrderId());

        try {
            var order = orderService.getOrder(event.getOrderId());
            order.changeStatus(com.example.mafiagame.order.domain.OrderStatus.CANCELLED);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[Consumer] 주문 취소 처리 실패: orderId={}", event.getOrderId(), e);
            throw e;
        }
    }
}
