package com.example.mafiagame.payment.domain;

/**
 * 결제 상태.
 */
public enum PaymentStatus {

    /** 결제 준비 중 (PG 승인 전). */
    READY,

    /** PG 결제 승인 완료. */
    DONE,

    /** 결제 취소/환불. */
    CANCELLED
}
