package com.example.mafiagame.payment.domain;

import java.time.LocalDateTime;

import com.example.mafiagame.order.domain.Order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 결제 엔티티.
 *
 * <p>PG사(Toss Payments)의 결제 승인 결과를 저장하며,
 * 정산 배치에서 settled 플래그를 기준으로 미정산 건을 집계한다.</p>
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_order", columnList = "order_id"),
        @Index(name = "idx_payment_settled", columnList = "settled, approvedAt")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @Column(length = 50)
    private String paymentId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    /** PG사 결제 키 (Toss paymentKey). */
    @Column(length = 100)
    private String pgPaymentKey;

    /** 결제 수단 (CARD, VIRTUAL_ACCOUNT, TRANSFER 등). */
    @Column(length = 30)
    private String method;

    /** 결제 금액 (원). */
    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.READY;

    /** PG 승인 시각. */
    private LocalDateTime approvedAt;

    /** 정산 완료 여부. */
    @Builder.Default
    @Column(nullable = false)
    private boolean settled = false;
}
