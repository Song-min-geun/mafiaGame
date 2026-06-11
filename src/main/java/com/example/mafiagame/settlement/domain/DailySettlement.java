package com.example.mafiagame.settlement.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 일일 정산 엔티티.
 *
 * <p>Spring Batch Job이 매일 집계한 결제/환불/수수료 정보를 저장한다.</p>
 */
@Entity
@Table(name = "daily_settlements", indexes = {
        @Index(name = "idx_settlement_date", columnList = "settlementDate", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailySettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 정산 대상 날짜. */
    @Column(nullable = false, unique = true)
    private LocalDate settlementDate;

    /** 총 주문 건수. */
    @Builder.Default
    @Column(nullable = false)
    private Integer totalOrderCount = 0;

    /** 총 매출액 (원). */
    @Builder.Default
    @Column(nullable = false)
    private Long totalSalesAmount = 0L;

    /** 총 환불액 (원). */
    @Builder.Default
    @Column(nullable = false)
    private Long totalRefundAmount = 0L;

    /** 순수익 (매출 - 환불 - 수수료). */
    @Builder.Default
    @Column(nullable = false)
    private Long netAmount = 0L;

    /** PG 수수료 (매출의 약 3.3%). */
    @Builder.Default
    @Column(nullable = false)
    private Long pgFeeAmount = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SettlementStatus status = SettlementStatus.PENDING;

    /** 정산 처리 시각. */
    private LocalDateTime processedAt;
}
