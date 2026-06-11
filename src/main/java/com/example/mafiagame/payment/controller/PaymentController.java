package com.example.mafiagame.payment.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.mafiagame.payment.domain.Payment;
import com.example.mafiagame.payment.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 결제 REST API 컨트롤러.
 *
 * <p>Toss Payments successUrl에서 리다이렉트된 요청을 처리한다.</p>
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "결제", description = "PG 결제 승인 및 취소 API")
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 결제 승인 요청.
     *
     * <p>Toss Payments SDK에서 결제 성공 후 전달되는
     * paymentKey, orderId, amount를 받아 승인을 완료한다.</p>
     */
    @PostMapping("/confirm")
    @Operation(summary = "결제 승인", description = "PG사 결제를 최종 승인합니다.")
    public ResponseEntity<Payment> confirmPayment(@RequestBody Map<String, Object> request) {
        String paymentKey = (String) request.get("paymentKey");
        String orderId = (String) request.get("orderId");
        Integer amount = (Integer) request.get("amount");

        Payment payment = paymentService.confirmPayment(paymentKey, orderId, amount);
        return ResponseEntity.ok(payment);
    }

    /**
     * 결제 취소(환불) 요청.
     */
    @PostMapping("/cancel")
    @Operation(summary = "결제 취소", description = "승인된 결제를 취소(환불)합니다.")
    public ResponseEntity<Void> cancelPayment(@RequestBody Map<String, String> request) {
        paymentService.cancelPayment(request.get("orderId"), request.get("cancelReason"));
        return ResponseEntity.ok().build();
    }
}
