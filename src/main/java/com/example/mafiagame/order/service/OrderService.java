package com.example.mafiagame.order.service;

import java.util.List;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.mafiagame.inventory.domain.Item;
import com.example.mafiagame.inventory.repository.ItemRepository;
import com.example.mafiagame.kafka.config.KafkaTopics;
import com.example.mafiagame.kafka.event.OrderEvent;
import com.example.mafiagame.order.domain.Order;
import com.example.mafiagame.order.domain.OrderItem;
import com.example.mafiagame.order.domain.OrderStatus;
import com.example.mafiagame.order.dto.OrderCreateRequest;
import com.example.mafiagame.order.repository.OrderRepository;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주문 생성/상태 변경 서비스.
 *
 * <p>주문 상태가 변경될 때 Kafka 이벤트를 발행하여
 * 결제, 아이템 지급, 정산 등 후속 처리를 트리거한다.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ItemRepository itemRepository;
    private final UsersRepository usersRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 주문 생성.
     *
     * @param userId  사용자 로그인 ID
     * @param request 주문 요청 DTO
     * @return 생성된 주문
     */
    @Transactional
    public Order createOrder(String userId, OrderCreateRequest request) {
        Users user = usersRepository.findByUserLoginId(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        Order order = Order.createNew(user, 0);
        int totalAmount = 0;

        for (OrderCreateRequest.ItemRequest itemReq : request.getItems()) {
            Item item = itemRepository.findById(itemReq.getItemId())
                    .orElseThrow(() -> new IllegalArgumentException("아이템을 찾을 수 없습니다: " + itemReq.getItemId()));

            if (!item.isActive()) {
                throw new IllegalStateException("판매 중지된 아이템입니다: " + item.getItemName());
            }

            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .item(item)
                    .quantity(itemReq.getQuantity())
                    .unitPrice(item.getPrice())
                    .build();

            order.getItems().add(orderItem);
            totalAmount += item.getPrice() * itemReq.getQuantity();
        }

        order.setTotalAmount(totalAmount);
        Order saved = orderRepository.save(order);

        // Kafka 이벤트 발행
        publishOrderEvent(saved, OrderEvent.EventType.ORDER_CREATED);

        log.info("[주문 생성] orderId={}, userId={}, amount={}", saved.getOrderId(), userId, totalAmount);
        return saved;
    }

    /**
     * 주문 상태를 PAID로 변경 (결제 완료 시 Kafka Consumer가 호출).
     *
     * @param orderId 주문 ID
     */
    @Transactional
    public void markAsPaid(String orderId) {
        Order order = getOrder(orderId);
        order.changeStatus(OrderStatus.PAID);
        orderRepository.save(order);
        log.info("[주문 상태 변경] orderId={}, PENDING → PAID", orderId);
    }

    /**
     * 주문 상태를 COMPLETED로 변경하고 Kafka 이벤트 발행 (아이템 지급 트리거).
     *
     * @param orderId 주문 ID
     */
    @Transactional
    public void completeOrder(String orderId) {
        Order order = getOrder(orderId);
        order.changeStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        publishOrderEvent(order, OrderEvent.EventType.ORDER_COMPLETED);
        log.info("[주문 완료] orderId={}", orderId);
    }

    /**
     * 주문 환불 처리.
     *
     * @param orderId 주문 ID
     */
    @Transactional
    public void refundOrder(String orderId) {
        Order order = getOrder(orderId);
        order.changeStatus(OrderStatus.REFUNDED);
        orderRepository.save(order);

        publishOrderEvent(order, OrderEvent.EventType.ORDER_REFUNDED);
        log.info("[주문 환불] orderId={}", orderId);
    }

    /**
     * 주문 단건 조회.
     */
    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));
    }

    /**
     * 사용자 주문 목록 조회.
     */
    public List<Order> getUserOrders(Long userId) {
        return orderRepository.findByUser_UserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 주문 이벤트를 Kafka로 발행한다.
     */
    private void publishOrderEvent(Order order, OrderEvent.EventType eventType) {
        List<OrderEvent.OrderItemInfo> itemInfos = order.getItems().stream()
                .map(oi -> OrderEvent.OrderItemInfo.builder()
                        .itemId(oi.getItem().getItemId())
                        .itemName(oi.getItem().getItemName())
                        .quantity(oi.getQuantity())
                        .unitPrice(oi.getUnitPrice())
                        .build())
                .toList();

        OrderEvent event = OrderEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(order.getOrderId())
                .userId(order.getUser().getUserLoginId())
                .eventType(eventType)
                .totalAmount(order.getTotalAmount())
                .items(itemInfos)
                .build();

        String topic = switch (eventType) {
            case ORDER_CREATED -> KafkaTopics.ORDER_CREATED;
            case ORDER_COMPLETED -> KafkaTopics.ORDER_COMPLETED;
            case ORDER_REFUNDED -> KafkaTopics.ORDER_REFUNDED;
            default -> KafkaTopics.ORDER_CREATED;
        };

        kafkaTemplate.send(topic, order.getOrderId(), event);
        log.debug("[Kafka 발행] topic={}, orderId={}", topic, order.getOrderId());
    }
}
