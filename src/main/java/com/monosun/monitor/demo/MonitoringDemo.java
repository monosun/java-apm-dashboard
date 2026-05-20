package com.monosun.monitor.demo;

import com.monosun.monitor.config.MonitorConfig;
import com.monosun.monitor.core.*;
import com.monosun.monitor.demo.service.OrderService;
import com.monosun.monitor.demo.service.OrderServiceImpl;
import com.monosun.monitor.integration.proxy.TracingProxy;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * java-monitor 데모 애플리케이션.
 *
 * 실행 방법:
 *   mvn package
 *   java -jar target/java-monitor-1.0.0.jar
 *
 * 메트릭 확인:
 *   http://localhost:9090/metrics   (Prometheus 형식)
 *   http://localhost:9090/health
 *   http://localhost:9090/jvm
 *   http://localhost:9090/traces
 */
public class MonitoringDemo {

    private static final Logger LOG = Logger.getLogger(MonitoringDemo.class.getName());

    public static void main(String[] args) throws Exception {

        // ──────────────────────────────────────────────────────────────────────
        // 1. 설정 로드 후 MonitoringAgent 초기화
        //    우선순위: -Dserver.xxx 시스템속성 > 외부 monitor.properties > 기본값
        // ──────────────────────────────────────────────────────────────────────
        MonitorConfig config = (args.length > 0) ? MonitorConfig.loadFrom(args[0]) : MonitorConfig.load();

        MonitoringAgent agent = MonitoringAgent.builder()
            .config(config)
            .build()
            .start();

        TransactionTracer tracer = agent.tracer();

        // ──────────────────────────────────────────────────────────────────────
        // 2. TracingProxy 를 사용한 자동 @Trace 추적
        //    → Spring 없이 인터페이스 기반 자동 추적
        // ──────────────────────────────────────────────────────────────────────
        OrderService orderService = TracingProxy.wrap(OrderService.class, new OrderServiceImpl());

        System.out.println("\n===== [데모 1] TracingProxy 자동 추적 =====");
        runOrderScenario(orderService);

        // ──────────────────────────────────────────────────────────────────────
        // 3. 수동 Span 추적 (라이브러리 없는 환경)
        // ──────────────────────────────────────────────────────────────────────
        System.out.println("\n===== [데모 2] 수동 Span 추적 =====");
        runManualTracing(tracer);

        // ──────────────────────────────────────────────────────────────────────
        // 4. 람다 기반 추적
        // ──────────────────────────────────────────────────────────────────────
        System.out.println("\n===== [데모 3] 람다 기반 추적 =====");
        runLambdaTracing(tracer);

        // ──────────────────────────────────────────────────────────────────────
        // 5. 멀티스레드 부하 시뮬레이션
        // ──────────────────────────────────────────────────────────────────────
        System.out.println("\n===== [데모 4] 멀티스레드 부하 시뮬레이션 =====");
        runLoadSimulation(orderService, tracer);

        // ──────────────────────────────────────────────────────────────────────
        // 6. JVM 상태 즉시 출력
        // ──────────────────────────────────────────────────────────────────────
        agent.printJvmSnapshot();

        // ──────────────────────────────────────────────────────────────────────
        // 7. 최근 스팬 출력
        // ──────────────────────────────────────────────────────────────────────
        System.out.println("\n===== [데모 5] 최근 스팬 목록 =====");
        printRecentSpans(agent.spanStorage());

        System.out.println("\n메트릭 서버가 실행 중입니다. 브라우저에서 확인하세요:");
        System.out.println("  http://localhost:9090/dashboard  (실시간 대시보드)");
        System.out.println("  http://localhost:9090/metrics    (Prometheus)");
        System.out.println("  http://localhost:9090/health");
        System.out.println("  http://localhost:9090/jvm");
        System.out.println("  http://localhost:9090/traces");
        System.out.println("  http://localhost:9090/api/stats");
        System.out.println("\nCtrl+C 로 종료합니다.");

        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop));
        Thread.currentThread().join(); // 데몬 스레드가 종료되지 않도록 대기
    }

    // ── 데모 메서드들 ──────────────────────────────────────────────────────────

    private static void runOrderScenario(OrderService orderService) {
        // 정상 흐름
        TraceContext ctx = TraceContext.startTrace();
        try {
            String orderId = orderService.createOrder("PROD-001", 3);
            boolean safe   = orderService.riskCheck(orderId);
            if (safe) {
                List<String> orders = orderService.listOrders("USER-001");
                System.out.println("  조회된 주문 수: " + orders.size());
            }
        } finally {
            TraceContext.clear();
        }

        // 취소 실패 포함 흐름
        for (int i = 0; i < 5; i++) {
            TraceContext ctx2 = TraceContext.startTrace();
            try {
                String orderId = orderService.createOrder("PROD-00" + i, 1);
                orderService.cancelOrder(orderId);
            } catch (Exception e) {
                System.out.println("  [예상된 오류] " + e.getMessage());
            } finally {
                TraceContext.clear();
            }
        }
    }

    private static void runManualTracing(TransactionTracer tracer) throws InterruptedException {
        // 루트 스팬 시작
        TraceContext ctx = TraceContext.startTrace();
        Span rootSpan = ctx.startSpan("payment.process");
        rootSpan.tag("payment.method", "card");
        rootSpan.tag("currency", "KRW");

        try {
            // 중첩 스팬 — 카드 검증
            Span validateSpan = ctx.startSpan("payment.validate");
            validateSpan.tag("step", "card_validation");
            Thread.sleep(80);
            ctx.finishSpan(validateSpan);

            // 중첩 스팬 — PG 연동
            Span pgSpan = ctx.startSpan("payment.pg.charge");
            pgSpan.tag("pg", "KakaoPay");
            Thread.sleep(200);
            ctx.finishSpan(pgSpan);

            // 중첩 스팬 — 영수증 발행
            Span receiptSpan = ctx.startSpan("payment.receipt");
            Thread.sleep(30);
            ctx.finishSpan(receiptSpan);

            ctx.finishSpan(rootSpan);
            System.out.println("  결제 완료: traceId=" + ctx.getTraceId());
        } catch (Exception e) {
            ctx.finishSpan(rootSpan, e);
        } finally {
            TraceContext.clear();
        }
    }

    private static void runLambdaTracing(TransactionTracer tracer) {
        // 반환값 없는 작업
        tracer.trace("notification.send", () -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.println("  알림 전송 완료");
        });

        // 반환값 있는 작업
        String result = tracer.trace("user.fetch", () -> {
            try { Thread.sleep(70); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "USER-홍길동";
        });
        System.out.println("  조회된 사용자: " + result);

        // 오류 발생 추적
        try {
            tracer.trace("inventory.deduct", () -> {
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new RuntimeException("재고 부족");
            });
        } catch (RuntimeException e) {
            System.out.println("  [예상된 오류] " + e.getMessage());
        }
    }

    private static void runLoadSimulation(OrderService orderService, TransactionTracer tracer)
        throws InterruptedException {

        int threadCount = 5;
        int requestsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount   = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int r = 0; r < requestsPerThread; r++) {
                        TraceContext ctx = TraceContext.startTrace();
                        try {
                            String orderId = orderService.createOrder("PROD-" + threadId, r + 1);
                            orderService.cancelOrder(orderId);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        } finally {
                            TraceContext.clear();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.printf("  부하 시뮬레이션 완료: 성공=%d, 실패=%d, 전체=%d%n",
            successCount.get(), errorCount.get(), threadCount * requestsPerThread);
    }

    private static void printRecentSpans(SpanStorage storage) {
        List<Span> recent = storage.getRecentSpans(10);
        System.out.printf("  최근 %d개 스팬:%n", recent.size());
        for (Span span : recent) {
            System.out.printf("    %s%n", span);
        }
        System.out.printf("  전체 통계: 총 %d건, 오류 %d건, 평균 %.1fms%n",
            storage.getTotalSpans(),
            storage.getErrorSpans(),
            storage.getAverageDurationMillis());
    }
}
