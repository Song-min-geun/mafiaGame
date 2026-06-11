package com.example.mafiagame.order.domain;

/**
 * 주문 상태.
 *
 * <p>PENDING → PAID → COMPLETED 또는 CANCELLED/REFUNDED 순서로 전이한다.</p>
 */
public enum OrderStatus {

    /** 주문 생성됨, 결제 대기 중. */
    PENDING,

    /** PG사 결제 승인 완료. */
    PAID,

    /** 아이템 지급까지 완료. */
    COMPLETED,

    /** 결제 전 취소. */
    CANCELLED,

    /** 결제 후 환불 처리. */
    REFUNDED
}
