package com.example.mafiagame.payment.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Toss Payments API 클라이언트.
 *
 * <p>결제 승인, 취소 등 PG사 API를 호출한다.
 * Secret Key는 Basic Auth 헤더로 전달한다.</p>
 *
 * @see <a href="https://docs.tosspayments.com/reference">Toss Payments API 문서</a>
 */
@Component
@Slf4j
public class TossPaymentClient {

    @Value("${toss.payments.secret-key}")
    private String secretKey;

    @Value("${toss.payments.base-url}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 결제 승인 API 호출.
     *
     * @param paymentKey PG사 결제 키
     * @param orderId    주문 ID
     * @param amount     결제 금액
     * @return PG사 응답 (JSON Map)
     */
    public Map<String, Object> confirmPayment(String paymentKey, String orderId, Integer amount) {
        log.info("[Toss] 결제 승인 요청: paymentKey={}, orderId={}, amount={}", paymentKey, orderId, amount);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClient.post()
                .uri("/payments/confirm")
                .bodyValue(Map.of(
                        "paymentKey", paymentKey,
                        "orderId", orderId,
                        "amount", amount))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        log.info("[Toss] 결제 승인 응답: orderId={}, status={}", orderId,
                response != null ? response.get("status") : "null");
        return response;
    }

    /**
     * 결제 취소(환불) API 호출.
     *
     * @param paymentKey   PG사 결제 키
     * @param cancelReason 취소 사유
     * @return PG사 응답 (JSON Map)
     */
    public Map<String, Object> cancelPayment(String paymentKey, String cancelReason) {
        log.info("[Toss] 결제 취소 요청: paymentKey={}", paymentKey);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClient.post()
                .uri("/payments/{paymentKey}/cancel", paymentKey)
                .bodyValue(Map.of("cancelReason", cancelReason))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        log.info("[Toss] 결제 취소 응답: paymentKey={}", paymentKey);
        return response;
    }
}
