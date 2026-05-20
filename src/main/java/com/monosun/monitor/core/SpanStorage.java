package com.monosun.monitor.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * 완료된 스팬을 고정 크기 링 버퍼에 저장합니다.
 * Thread-safe하며 오버플로우 시 오래된 항목을 자동으로 제거합니다.
 */
public class SpanStorage {

    private static final int DEFAULT_CAPACITY = 1000;

    private final ArrayBlockingQueue<Span> buffer;
    private final LongAdder totalSpans     = new LongAdder();
    private final LongAdder errorSpans     = new LongAdder();
    private final LongAdder totalDuration  = new LongAdder(); // ms 합계

    public SpanStorage() {
        this(DEFAULT_CAPACITY);
    }

    public SpanStorage(int capacity) {
        this.buffer = new ArrayBlockingQueue<>(capacity);
    }

    public void store(Span span) {
        if (!span.isFinished()) return;

        // 버퍼가 가득 차면 가장 오래된 항목 제거
        if (!buffer.offer(span)) {
            buffer.poll();
            buffer.offer(span);
        }

        totalSpans.increment();
        totalDuration.add(span.getDurationMillis());
        if ("ERROR".equals(span.getStatus())) {
            errorSpans.increment();
        }
    }

    public List<Span> getRecentSpans(int limit) {
        List<Span> result = new ArrayList<>(buffer);
        int fromIndex = Math.max(0, result.size() - limit);
        return result.subList(fromIndex, result.size());
    }

    public long getTotalSpans()    { return totalSpans.sum(); }
    public long getErrorSpans()    { return errorSpans.sum(); }
    public long getTotalDuration() { return totalDuration.sum(); }

    public double getAverageDurationMillis() {
        long total = totalSpans.sum();
        return total == 0 ? 0.0 : (double) totalDuration.sum() / total;
    }

    public double getErrorRate() {
        long total = totalSpans.sum();
        return total == 0 ? 0.0 : (double) errorSpans.sum() / total * 100.0;
    }

    public void reset() {
        buffer.clear();
        totalSpans.reset();
        errorSpans.reset();
        totalDuration.reset();
    }
}
