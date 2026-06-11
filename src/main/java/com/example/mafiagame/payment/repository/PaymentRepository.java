package com.example.mafiagame.payment.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.mafiagame.payment.domain.Payment;
import com.example.mafiagame.payment.domain.PaymentStatus;

/**
 * 결제 JPA 리포지토리.
 */
public interface PaymentRepository extends JpaRepository<Payment, String> {

    /**
     * 주문 ID로 결제 조회.
     */
    Optional<Payment> findByOrder_OrderId(String orderId);

    /**
     * 미정산 + 승인 완료 건 페이징 조회 (Batch Reader용).
     */
    Page<Payment> findBySettledFalseAndStatusAndApprovedAtBetween(
            PaymentStatus status,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable);
}
