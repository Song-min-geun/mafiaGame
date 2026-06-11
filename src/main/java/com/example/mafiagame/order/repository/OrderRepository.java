package com.example.mafiagame.order.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.mafiagame.order.domain.Order;
import com.example.mafiagame.order.domain.OrderStatus;

/**
 * 주문 JPA 리포지토리.
 */
public interface OrderRepository extends JpaRepository<Order, String> {

    /**
     * 특정 사용자의 주문 목록 조회 (최신순).
     */
    List<Order> findByUser_UserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 특정 상태의 주문 조회.
     */
    List<Order> findByStatus(OrderStatus status);
}
