package com.monosun.monitor.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * ThreadLocal 기반의 트레이스 컨텍스트.
 * 중첩된 메서드 호출을 스택으로 추적합니다.
 *
 * 사용 패턴:
 *   TraceContext ctx = TraceContext.startTrace();
 *   Span span = ctx.startSpan("my.operation");
 *   try {
 *       // 작업 수행
 *   } finally {
 *       ctx.finishSpan(span);
 *   }
 */
public class TraceContext {

    private static final ThreadLocal<TraceContext> LOCAL = new ThreadLocal<>();

    private final String traceId;
    private final Deque<Span> spanStack = new ArrayDeque<>();

    private TraceContext(String traceId) {
        this.traceId = traceId;
    }

    // -------------------------------------------------------------------------
    // 컨텍스트 생명주기
    // -------------------------------------------------------------------------

    public static TraceContext startTrace() {
        return startTrace(generateTraceId());
    }

    /** 외부 트레이스 ID (HTTP 헤더 등) 를 받아 컨텍스트 시작 */
    public static TraceContext startTrace(String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        LOCAL.set(ctx);
        return ctx;
    }

    public static TraceContext current() {
        return LOCAL.get();
    }

    /** 스레드 종료 또는 요청 처리 완료 시 반드시 호출 */
    public static void clear() {
        LOCAL.remove();
    }

    // -------------------------------------------------------------------------
    // 스팬 생명주기
    // -------------------------------------------------------------------------

    public Span startSpan(String operationName) {
        String parentId = spanStack.isEmpty() ? null : spanStack.peek().getSpanId();
        Span span = new Span(traceId, parentId, operationName);
        spanStack.push(span);
        return span;
    }

    public void finishSpan(Span span) {
        span.finish();
        spanStack.remove(span);
        TransactionTracer.getInstance().onSpanFinished(span);
    }

    public void finishSpan(Span span, Throwable error) {
        span.finishWithError(error);
        spanStack.remove(span);
        TransactionTracer.getInstance().onSpanFinished(span);
    }

    public Span currentSpan() {
        return spanStack.isEmpty() ? null : spanStack.peek();
    }

    public boolean hasActiveSpans() {
        return !spanStack.isEmpty();
    }

    public String getTraceId() { return traceId; }

    private static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
