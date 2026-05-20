package com.monosun.monitor.exporter;

import com.monosun.monitor.core.JvmMetricsCollector;
import com.monosun.monitor.core.Span;
import com.monosun.monitor.core.SpanStorage;

import java.util.List;
import java.util.Map;

/**
 * Prometheus 텍스트 형식(0.0.4)으로 메트릭을 출력합니다.
 * Prometheus scrape_config 에서 직접 수집 가능합니다.
 */
public class PrometheusExporter {

    private final JvmMetricsCollector jvm;
    private final SpanStorage storage;

    public PrometheusExporter(JvmMetricsCollector jvm, SpanStorage storage) {
        this.jvm     = jvm;
        this.storage = storage;
    }

    public String export() {
        StringBuilder sb = new StringBuilder();

        // ── JVM Memory ────────────────────────────────────────────────────────
        gauge(sb, "jvm_heap_used_bytes",      "JVM heap memory used (bytes)",      jvm.heapUsedBytes());
        gauge(sb, "jvm_heap_max_bytes",       "JVM heap memory max (bytes)",       jvm.heapMaxBytes());
        gauge(sb, "jvm_heap_committed_bytes", "JVM heap memory committed (bytes)", jvm.heapCommittedBytes());
        gauge(sb, "jvm_nonheap_used_bytes",   "JVM non-heap memory used (bytes)",  jvm.nonHeapUsedBytes());
        gauge(sb, "jvm_metaspace_used_bytes", "JVM Metaspace used (bytes)",        jvm.metaspaceUsedBytes());
        gauge(sb, "jvm_heap_usage_percent",   "JVM heap usage percentage",         jvm.heapUsagePercent());

        // ── GC ────────────────────────────────────────────────────────────────
        counter(sb, "jvm_gc_collection_total",   "Total GC collection count",    jvm.gcTotalCount());
        counter(sb, "jvm_gc_collection_time_ms", "Total GC collection time (ms)", jvm.gcTotalTimeMs());

        for (Map.Entry<String, long[]> entry : jvm.gcDetails().entrySet()) {
            String gcName = sanitize(entry.getKey());
            long[] vals   = entry.getValue();
            labeledCounter(sb, "jvm_gc_collector_count",   "gc", gcName, vals[0]);
            labeledCounter(sb, "jvm_gc_collector_time_ms", "gc", gcName, vals[1]);
        }

        // ── Threads ───────────────────────────────────────────────────────────
        gauge(sb, "jvm_threads_current",        "Current thread count",                    jvm.threadCount());
        gauge(sb, "jvm_threads_daemon",         "Daemon thread count",                     jvm.daemonThreadCount());
        gauge(sb, "jvm_threads_peak",           "Peak thread count",                       jvm.peakThreadCount());
        gauge(sb, "jvm_threads_runnable",       "Threads in RUNNABLE state",               jvm.runnableThreadCount());
        gauge(sb, "jvm_threads_blocked",        "Threads in BLOCKED state",                jvm.blockedThreadCount());
        gauge(sb, "jvm_threads_waiting",        "Threads in WAITING state",                jvm.waitingThreadCount());
        gauge(sb, "jvm_threads_timed_waiting",  "Threads in TIMED_WAITING state",          jvm.timedWaitingThreadCount());
        counter(sb, "jvm_threads_started_total","Total started threads",                   jvm.totalStartedThreads());

        long[] deadlocked = jvm.findDeadlockedThreads();
        gauge(sb, "jvm_threads_deadlocked",     "Deadlocked thread count",                 deadlocked.length);

        // ── CPU ───────────────────────────────────────────────────────────────
        gauge(sb, "jvm_cpu_process_usage",     "Process CPU usage (0-1)",        jvm.processCpuLoad());
        gauge(sb, "jvm_cpu_system_usage",      "System CPU usage (0-1)",         jvm.systemCpuLoad());
        gauge(sb, "jvm_cpu_processors",        "Available processors",            jvm.availableProcessors());

        // ── OS Memory ─────────────────────────────────────────────────────────
        gauge(sb, "os_memory_total_bytes",     "Total physical memory (bytes)",   jvm.totalPhysicalMemoryBytes());
        gauge(sb, "os_memory_free_bytes",      "Free physical memory (bytes)",    jvm.freePhysicalMemoryBytes());
        gauge(sb, "os_swap_total_bytes",       "Total swap space (bytes)",        jvm.totalSwapSpaceBytes());
        gauge(sb, "os_swap_free_bytes",        "Free swap space (bytes)",         jvm.freeSwapSpaceBytes());
        gauge(sb, "os_virtual_memory_bytes",   "Committed virtual memory (bytes)",jvm.committedVirtualMemoryBytes());

        // ── Buffer Pools ──────────────────────────────────────────────────────
        gauge(sb, "jvm_buffer_direct_count",   "Direct buffer pool count",        jvm.directBufferCount());
        gauge(sb, "jvm_buffer_direct_bytes",   "Direct buffer memory used (bytes)",jvm.directBufferUsedBytes());
        gauge(sb, "jvm_buffer_direct_capacity","Direct buffer total capacity (bytes)",jvm.directBufferCapacityBytes());
        gauge(sb, "jvm_buffer_mapped_count",   "Mapped buffer pool count",        jvm.mappedBufferCount());
        gauge(sb, "jvm_buffer_mapped_bytes",   "Mapped buffer memory used (bytes)",jvm.mappedBufferUsedBytes());

        // ── JIT Compilation ───────────────────────────────────────────────────
        counter(sb, "jvm_jit_compilation_time_ms", "Total JIT compilation time (ms)", jvm.jitCompilationTimeMs());

        // ── Memory Pools (per pool) ───────────────────────────────────────────
        sb.append("# HELP jvm_memory_pool_used_bytes Memory pool used bytes\n");
        sb.append("# TYPE jvm_memory_pool_used_bytes gauge\n");
        jvm.memoryPoolDetails().forEach((name, vals) ->
            sb.append(String.format("jvm_memory_pool_used_bytes{pool=\"%s\"} %d%n", sanitize(name), vals[0])));

        // ── Runtime ───────────────────────────────────────────────────────────
        counter(sb, "jvm_uptime_ms",            "JVM uptime (ms)",                jvm.uptimeMs());
        gauge(sb,  "jvm_classes_loaded",         "Currently loaded classes",       jvm.loadedClassCount());
        counter(sb,"jvm_classes_loaded_total",   "Total loaded class count",       jvm.totalLoadedClasses());

        // ── Span / Transaction Metrics ─────────────────────────────────────────
        counter(sb, "trace_spans_total",         "Total completed spans",         storage.getTotalSpans());
        counter(sb, "trace_spans_error_total",   "Total error spans",             storage.getErrorSpans());
        gauge(sb,   "trace_error_rate_percent",  "Span error rate (%)",           storage.getErrorRate());
        gauge(sb,   "trace_avg_duration_ms",     "Average span duration (ms)",    storage.getAverageDurationMillis());

        // 최근 스팬별 duration 히스토그램 (단순 버킷)
        appendSpanHistogram(sb);

        return sb.toString();
    }

    private void appendSpanHistogram(StringBuilder sb) {
        List<Span> recent = storage.getRecentSpans(500);
        if (recent.isEmpty()) return;

        long[] buckets = {10, 50, 100, 200, 500, 1000, Long.MAX_VALUE};
        String[] labels = {"10", "50", "100", "200", "500", "1000", "+Inf"};
        long[] counts = new long[buckets.length];

        for (Span span : recent) {
            long d = span.getDurationMillis();
            for (int i = 0; i < buckets.length; i++) {
                if (d <= buckets[i]) { counts[i]++; break; }
            }
        }

        sb.append("# HELP trace_span_duration_ms_bucket Span duration histogram\n");
        sb.append("# TYPE trace_span_duration_ms_bucket gauge\n");
        for (int i = 0; i < labels.length; i++) {
            sb.append(String.format("trace_span_duration_ms_bucket{le=\"%s\"} %d%n", labels[i], counts[i]));
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private void gauge(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private void labeledCounter(StringBuilder sb, String name, String labelKey, String labelVal, long value) {
        sb.append(name).append('{').append(labelKey).append("=\"").append(labelVal).append("\"} ").append(value).append('\n');
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
}
