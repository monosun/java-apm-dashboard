package com.monosun.monitor.core;

import com.monosun.monitor.annotation.Trace;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 트랜잭션 추적 엔진.
 * - @Trace 어노테이션 처리
 * - 수동 span 시작/종료
 * - 완료된 span 을 SpanStorage 에 저장
 */
public class TransactionTracer {

    private static final Logger LOG = Logger.getLogger(TransactionTracer.class.getName());
    private static volatile TransactionTracer INSTANCE;

    private final SpanStorage storage;

    private TransactionTracer(SpanStorage storage) {
        this.storage = storage;
    }

    static void init(SpanStorage storage) {
        INSTANCE = new TransactionTracer(storage);
    }

    public static TransactionTracer getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("MonitoringAgent.start() 를 먼저 호출하세요.");
        }
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // 수동 추적 API
    // -------------------------------------------------------------------------

    /**
     * 새 스팬을 시작합니다.
     * TraceContext 가 없으면 새 트레이스를 자동으로 시작합니다.
     */
    public Span start(String operationName) {
        if (TraceContext.current() == null) {
            TraceContext.startTrace();
        }
        return TraceContext.current().startSpan(operationName);
    }

    public void finish(Span span) {
        TraceContext ctx = TraceContext.current();
        if (ctx != null) ctx.finishSpan(span);
        else { span.finish(); storage.store(span); }
    }

    public void finish(Span span, Throwable error) {
        TraceContext ctx = TraceContext.current();
        if (ctx != null) ctx.finishSpan(span, error);
        else { span.finishWithError(error); storage.store(span); }
    }

    // -------------------------------------------------------------------------
    // 람다 추적 API
    // -------------------------------------------------------------------------

    /** 반환값이 없는 작업을 추적합니다. */
    public void trace(String operationName, Runnable action) {
        Span span = start(operationName);
        try {
            action.run();
            finish(span);
        } catch (Throwable t) {
            finish(span, t);
            throw t;
        }
    }

    /** 반환값이 있는 작업을 추적합니다. */
    public <T> T trace(String operationName, Supplier<T> action) {
        Span span = start(operationName);
        try {
            T result = action.get();
            finish(span);
            return result;
        } catch (Throwable t) {
            finish(span, t);
            throw t;
        }
    }

    // -------------------------------------------------------------------------
    // @Trace 어노테이션 처리
    // -------------------------------------------------------------------------

    /**
     * 리플렉션으로 @Trace 어노테이션을 읽고 스팬을 생성합니다.
     * TracingProxy 에서 호출합니다.
     */
    public Span startFromAnnotation(Method method, Object[] args) {
        Trace trace = method.getAnnotation(Trace.class);
        if (trace == null) {
            trace = method.getDeclaringClass().getAnnotation(Trace.class);
        }

        String name = (trace != null && !trace.name().isEmpty())
            ? trace.name()
            : method.getDeclaringClass().getSimpleName() + "." + method.getName();

        Span span = start(name);
        span.tag("class",  method.getDeclaringClass().getName());
        span.tag("method", method.getName());

        if (trace != null) {
            for (String tag : trace.tags()) {
                String[] kv = tag.split("=", 2);
                if (kv.length == 2) span.tag(kv[0], kv[1]);
            }
            if (trace.logParams() && args != null) {
                span.tag("params", buildParamString(args));
            }
        }
        return span;
    }

    private String buildParamString(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] != null ? args[i].toString() : "null");
        }
        return sb.append("]").toString();
    }

    // -------------------------------------------------------------------------
    // 내부 콜백 (TraceContext → TransactionTracer)
    // -------------------------------------------------------------------------

    void onSpanFinished(Span span) {
        storage.store(span);
        if (LOG.isLoggable(java.util.logging.Level.FINE)) {
            LOG.fine(span.toString());
        }
    }

    public SpanStorage getStorage() { return storage; }
}
