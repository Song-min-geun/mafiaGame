package com.example.mafiagame.order.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.mafiagame.order.domain.Order;
import com.example.mafiagame.order.dto.OrderCreateRequest;
import com.example.mafiagame.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 주문 REST API 컨트롤러.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "주문", description = "주문 생성, 조회, 환불 API")
public class OrderController {

    private final OrderService orderService;

    /**
     * 주문 생성.
     */
    @PostMapping
    @Operation(summary = "주문 생성", description = "아이템을 선택하여 새 주문을 생성합니다.")
    public ResponseEntity<Order> createOrder(Principal principal, @Valid @RequestBody OrderCreateRequest request) {
        Order order = orderService.createOrder(principal.getName(), request);
        return ResponseEntity.ok(order);
    }

    /**
     * 주문 상세 조회.
     */
    @GetMapping("/{orderId}")
    @Operation(summary = "주문 조회", description = "주문 ID로 주문 상세를 조회합니다.")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    /**
     * 주문 환불 요청.
     */
    @PostMapping("/{orderId}/refund")
    @Operation(summary = "주문 환불", description = "결제 완료된 주문을 환불 처리합니다.")
    public ResponseEntity<Void> refundOrder(@PathVariable String orderId) {
        orderService.refundOrder(orderId);
        return ResponseEntity.ok().build();
    }
}
