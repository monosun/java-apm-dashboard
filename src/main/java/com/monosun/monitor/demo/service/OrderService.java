package com.monosun.monitor.demo.service;

import com.monosun.monitor.annotation.Trace;

import java.util.List;

/** 주문 서비스 인터페이스 — TracingProxy 예제용 */
@Trace(tags = {"layer=service"})
public interface OrderService {

    @Trace(name = "order.create", logParams = true)
    String createOrder(String productId, int quantity);

    @Trace(name = "order.list")
    List<String> listOrders(String userId);

    @Trace(name = "order.cancel")
    void cancelOrder(String orderId);

    @Trace(name = "order.risk.check", tags = {"type=validation"})
    boolean riskCheck(String orderId);
}
