package com.example.mafiagame.payment.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.mafiagame.kafka.config.KafkaTopics;
import com.example.mafiagame.kafka.event.PaymentEvent;
import com.example.mafiagame.order.domain.Order;
import com.example.mafiagame.order.repository.OrderRepository;
import com.example.mafiagame.payment.client.TossPaymentClient;
import com.example.mafiagame.payment.domain.Payment;
import com.example.mafiagame.payment.domain.PaymentStatus;
import com.example.mafiagame.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 서비스.
 *
 * <p>Toss Payments API를 호출하여 결제 승인/취소를 처리하고,
 * 결과를 Kafka 이벤트로 발행한다.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final TossPaymentClient tossPaymentClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * PG 결제 승인 처리.
     *
     * <p>Toss Payments의 successUrl에서 전달받은 paymentKey, orderId, amount로
     * 결제 승인 API를 호출한다.</p>
     *
     * @param paymentKey PG사 결제 키
     * @param orderId    주문 ID
     * @param amount     결제 금액
     * @return 저장된 Payment 엔티티
     */
    @Transactional
    public Payment confirmPayment(String paymentKey, String orderId, Integer amount) {
        // 주문 검증
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));

        if (!order.getTotalAmount().equals(amount)) {
            throw new IllegalArgumentException("결제 금액이 주문 금액과 일치하지 않습니다.");
        }

        // 중복 결제 방지
        if (paymentRepository.findByOrder_OrderId(orderId).isPresent()) {
            throw new IllegalStateException("이미 결제된 주문입니다: " + orderId);
        }

        try {
            // PG 결제 승인 API 호출
            Map<String, Object> pgResponse = tossPaymentClient.confirmPayment(paymentKey, orderId, amount);

            String paymentId = "PAY_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Payment payment = Payment.builder()
                    .paymentId(paymentId)
                    .order(order)
                    .pgPaymentKey(paymentKey)
                    .method(pgResponse != null ? (String) pgResponse.get("method") : "UNKNOWN")
                    .amount(amount)
                    .status(PaymentStatus.DONE)
                    .approvedAt(LocalDateTime.now())
                    .build();

            Payment saved = paymentRepository.save(payment);

            // Kafka: 결제 완료 이벤트 발행
            publishPaymentEvent(saved, PaymentEvent.EventType.PAYMENT_COMPLETED);

            log.info("[결제 승인] paymentId={}, orderId={}, amount={}", paymentId, orderId, amount);
            return saved;

        } catch (Exception e) {
            log.error("[결제 실패] orderId={}, error={}", orderId, e.getMessage(), e);

            // Kafka: 결제 실패 이벤트 발행
            PaymentEvent failEvent = PaymentEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderId(orderId)
                    .userId(order.getUser().getUserLoginId())
                    .eventType(PaymentEvent.EventType.PAYMENT_FAILED)
                    .amount(amount)
                    .build();
            kafkaTemplate.send(KafkaTopics.PAYMENT_FAILED, orderId, failEvent);

            throw new RuntimeException("결제 승인 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 결제 취소(환불) 처리.
     *
     * @param orderId      주문 ID
     * @param cancelReason 취소 사유
     */
    @Transactional
    public void cancelPayment(String orderId, String cancelReason) {
        Payment payment = paymentRepository.findByOrder_OrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("결제 정보를 찾을 수 없습니다: " + orderId));

        // PG 취소 API 호출
        tossPaymentClient.cancelPayment(payment.getPgPaymentKey(), cancelReason);

        payment.setStatus(PaymentStatus.CANCELLED);
        paymentRepository.save(payment);

        // Kafka: 결제 취소 이벤트 발행
        publishPaymentEvent(payment, PaymentEvent.EventType.PAYMENT_CANCELLED);

        log.info("[결제 취소] orderId={}, reason={}", orderId, cancelReason);
    }

    /**
     * 결제 이벤트를 Kafka로 발행한다.
     */
    private void publishPaymentEvent(Payment payment, PaymentEvent.EventType eventType) {
        PaymentEvent event = PaymentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .paymentId(payment.getPaymentId())
                .orderId(payment.getOrder().getOrderId())
                .userId(payment.getOrder().getUser().getUserLoginId())
                .eventType(eventType)
                .amount(payment.getAmount())
                .paymentMethod(payment.getMethod())
                .pgPaymentKey(payment.getPgPaymentKey())
                .build();

        String topic = switch (eventType) {
            case PAYMENT_COMPLETED -> KafkaTopics.PAYMENT_COMPLETED;
            case PAYMENT_FAILED -> KafkaTopics.PAYMENT_FAILED;
            case PAYMENT_CANCELLED -> KafkaTopics.PAYMENT_CANCELLED;
        };

        kafkaTemplate.send(topic, payment.getOrder().getOrderId(), event);
    }
}
