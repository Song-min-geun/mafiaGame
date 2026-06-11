package com.example.mafiagame.kafka.config;

/**
 * Kafka 토픽 이름 상수 정의.
 *
 * <p>
 * Producer/Consumer에서 공통으로 참조하여 토픽 이름 불일치를 방지한다.
 * </p>
 */
public final class KafkaTopics {

    private KafkaTopics() {
        // 인스턴스 생성 방지
    }

    /** 주문 생성 이벤트 토픽. */
    public static final String ORDER_CREATED = "order.created";

    /** 주문 완료 이벤트 토픽. */
    public static final String ORDER_COMPLETED = "order.completed";

    /** 주문 환불 이벤트 토픽. */
    public static final String ORDER_REFUNDED = "order.refunded";

    /** 결제 완료 이벤트 토픽. */
    public static final String PAYMENT_COMPLETED = "payment.completed";

    /** 결제 실패 이벤트 토픽. */
    public static final String PAYMENT_FAILED = "payment.failed";

    /** 결제 취소 이벤트 토픽. */
    public static final String PAYMENT_CANCELLED = "payment.cancelled";

    /** 게임 종료 이벤트 토픽. */
    public static final String GAME_ENDED = "game.ended";
}
