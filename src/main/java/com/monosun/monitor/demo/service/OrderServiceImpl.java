package com.monosun.monitor.demo.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 주문 서비스 구현체 — 실제 비즈니스 로직 시뮬레이션 */
public class OrderServiceImpl implements OrderService {

    @Override
    public String createOrder(String productId, int quantity) {
        simulateWork(50, 150);
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        System.out.println("  [OrderService] 주문 생성: " + orderId + " (상품=" + productId + ", 수량=" + quantity + ")");
        return orderId;
    }

    @Override
    public List<String> listOrders(String userId) {
        simulateWork(30, 80);
        return List.of("ORD-001", "ORD-002", "ORD-003");
    }

    @Override
    public void cancelOrder(String orderId) {
        simulateWork(20, 60);
        // 20% 확률로 실패 시뮬레이션
        if (ThreadLocalRandom.current().nextInt(5) == 0) {
            throw new IllegalStateException("이미 배송된 주문은 취소할 수 없습니다: " + orderId);
        }
        System.out.println("  [OrderService] 주문 취소: " + orderId);
    }

    @Override
    public boolean riskCheck(String orderId) {
        simulateWork(100, 300);
        boolean safe = ThreadLocalRandom.current().nextBoolean();
        System.out.println("  [OrderService] 위험 검사 결과: " + (safe ? "안전" : "위험"));
        return safe;
    }

    private void simulateWork(int minMs, int maxMs) {
        try {
            int sleep = ThreadLocalRandom.current().nextInt(minMs, maxMs);
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
