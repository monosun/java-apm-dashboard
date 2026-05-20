package com.monosun.monitor.core;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 단일 추적 단위 (Span).
 * 하나의 메서드 실행 또는 HTTP 요청에 대응합니다.
 */
public class Span {

    private final String spanId;
    private final String traceId;
    private final String parentSpanId;
    private final String operationName;
    private final long startNanos;
    private long endNanos;
    private boolean finished;
    private String status = "OK";
    private String errorType;
    private String errorMessage;
    private final Map<String, String> tags = new HashMap<>();
    private final Instant startInstant;

    Span(String traceId, String parentSpanId, String operationName) {
        this.spanId      = randomHex(16);
        this.traceId     = traceId;
        this.parentSpanId = parentSpanId;
        this.operationName = operationName;
        this.startNanos   = System.nanoTime();
        this.startInstant = Instant.now();
    }

    private static String randomHex(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void finish() {
        if (!finished) {
            this.endNanos = System.nanoTime();
            this.finished = true;
        }
    }

    public void finishWithError(Throwable t) {
        this.status       = "ERROR";
        this.errorType    = t.getClass().getSimpleName();
        this.errorMessage = t.getMessage();
        finish();
    }

    // -------------------------------------------------------------------------
    // Tags
    // -------------------------------------------------------------------------

    public Span tag(String key, String value) {
        tags.put(key, value);
        return this;
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    public long getDurationMillis() {
        return finished ? TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos) : 0;
    }

    public long getDurationMicros() {
        return finished ? TimeUnit.NANOSECONDS.toMicros(endNanos - startNanos) : 0;
    }

    public boolean isRoot() { return parentSpanId == null; }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getSpanId()        { return spanId; }
    public String getTraceId()       { return traceId; }
    public String getParentSpanId()  { return parentSpanId; }
    public String getOperationName() { return operationName; }
    public boolean isFinished()      { return finished; }
    public String getStatus()        { return status; }
    public String getErrorType()     { return errorType; }
    public String getErrorMessage()  { return errorMessage; }
    public Instant getStartInstant() { return startInstant; }
    public Map<String, String> getTags() { return Collections.unmodifiableMap(tags); }

    @Override
    public String toString() {
        return String.format("[%s] %s | trace=%s parent=%s | %dms | %s",
            spanId, operationName, traceId, parentSpanId, getDurationMillis(), status);
    }
}
