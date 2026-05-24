package com.monosun.monitor.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 대상 JVM 내부에서 실행되는 경량 HTTP 서버.
 * ThreadMonitorAgent 가 시작하며, 외부 모니터 대시보드에 스레드 정보를 제공합니다.
 *
 * 엔드포인트:
 *   GET /agent/health      — 헬스 체크
 *   GET /agent/info        — JVM / 에이전트 메타정보
 *   GET /agent/jvm         — JVM 메트릭 (Heap, GC, Thread 요약)
 *   GET /agent/threads     — 스레드 목록 (상위 5 프레임)
 *   GET /agent/thread/{id} — 단일 스레드 전체 스택 트레이스
 *   GET /agent/threaddump  — 전체 스레드 덤프 (text/plain, jstack 형식)
 *   GET /agent/deadlocks   — 데드락 감지 결과 (JSON)
 */
public class AgentHttpServer {

    private static final Logger LOG = Logger.getLogger(AgentHttpServer.class.getName());

    private final HttpServer           server;
    private final int                  port;
    private final AgentThreadCollector collector;
    private final long                 startTimeMs;

    public AgentHttpServer(int port) throws IOException {
        this.port        = port;
        this.collector   = new AgentThreadCollector();
        this.startTimeMs = System.currentTimeMillis();

        server = HttpServer.create(new InetSocketAddress(port), 32);

        server.createContext("/agent/health",    ex -> handle(ex, "application/json",
            () -> "{\"status\":\"UP\",\"port\":" + port + "}"));

        server.createContext("/agent/info",      ex -> handle(ex, "application/json",
            this::buildInfo));

        server.createContext("/agent/jvm",       ex -> handle(ex, "application/json",
            this::buildJvmJson));

        server.createContext("/agent/threads",   ex -> handle(ex, "application/json",
            () -> listToJson(collector.getThreadList(5))));

        server.createContext("/agent/thread/",   ex -> handle(ex, "application/json",
            () -> {
                String path = ex.getRequestURI().getPath(); // /agent/thread/123
                String idStr = path.substring("/agent/thread/".length()).replaceAll("[^0-9]", "");
                if (idStr.isEmpty()) return "{\"error\":\"id required\"}";
                Map<String, Object> detail = collector.getThreadDetail(Long.parseLong(idStr));
                return detail != null ? mapToJson(detail) : "{\"error\":\"thread not found\"}";
            }));

        server.createContext("/agent/threaddump", ex -> handle(ex, "text/plain; charset=UTF-8",
            collector::getThreadDump));

        server.createContext("/agent/deadlocks",  ex -> handle(ex, "application/json",
            () -> listToJson(collector.getDeadlockInfo())));

        server.createContext("/agent/top10",      ex -> handle(ex, "application/json",
            () -> listToJson(collector.getTopByProcessingTime(10))));

        server.createContext("/agent/requests",   ex -> handle(ex, "application/json",
            () -> listToJson(collector.getRequestProcessors())));

        server.createContext("/agent/dbpools",    ex -> handle(ex, "application/json",
            () -> listToJson(collector.getConnectionPools())));

        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "agent-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
        LOG.info("[ThreadMonitorAgent] 시작 — port:" + port
            + "  threads: http://localhost:" + port + "/agent/threads"
            + "  dump: http://localhost:" + port + "/agent/threaddump");
    }

    public void stop() { server.stop(0); }
    public int  getPort() { return port; }

    // ── HTTP 처리 ─────────────────────────────────────────────────────────────

    private void handle(HttpExchange ex, String ct, ResponseSupplier s) {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            String body  = s.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            try {
                byte[] err = ("Error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/plain");
                ex.sendResponseHeaders(500, err.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(err); }
            } catch (IOException ignored) {}
        }
    }

    // ── JSON 빌더 ─────────────────────────────────────────────────────────────

    private String buildInfo() {
        RuntimeMXBean rt  = ManagementFactory.getRuntimeMXBean();
        String jvmName    = esc(rt.getVmName());
        String jvmVersion = esc(rt.getVmVersion());
        long   pid        = ProcessHandle.current().pid();
        long   uptimeMs   = System.currentTimeMillis() - startTimeMs;
        return "{\"agentVersion\":\"1.10.0\""
            + ",\"startTime\":\"" + Instant.ofEpochMilli(startTimeMs) + "\""
            + ",\"uptimeMs\":" + uptimeMs
            + ",\"jvmName\":\"" + jvmName + "\""
            + ",\"jvmVersion\":\"" + jvmVersion + "\""
            + ",\"pid\":" + pid
            + ",\"port\":" + port + "}";
    }

    private String buildJvmJson() {
        MemoryMXBean                 memMX      = ManagementFactory.getMemoryMXBean();
        ThreadMXBean                 thrMX      = ManagementFactory.getThreadMXBean();
        RuntimeMXBean                rtMX       = ManagementFactory.getRuntimeMXBean();
        List<GarbageCollectorMXBean> gcMXBeans  = ManagementFactory.getGarbageCollectorMXBeans();
        List<MemoryPoolMXBean>       memPools   = ManagementFactory.getMemoryPoolMXBeans();

        MemoryUsage heap    = memMX.getHeapMemoryUsage();
        MemoryUsage nonHeap = memMX.getNonHeapMemoryUsage();
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : gcMXBeans) {
            if (gc.getCollectionCount() >= 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime()  >= 0) gcTime  += gc.getCollectionTime();
        }
        long metaspace = memPools.stream()
            .filter(p -> p.getName().contains("Metaspace"))
            .mapToLong(p -> p.getUsage().getUsed() / 1_048_576L)
            .findFirst().orElse(-1L);

        ThreadInfo[] infos = thrMX.getThreadInfo(thrMX.getAllThreadIds(), 0);
        int blocked = 0, waiting = 0, timed = 0, runnable = 0;
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            switch (ti.getThreadState()) {
                case BLOCKED       -> blocked++;
                case WAITING       -> waiting++;
                case TIMED_WAITING -> timed++;
                case RUNNABLE      -> runnable++;
            }
        }
        double heapPct = heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() * 100.0 : 0.0;

        double cpuLoad   = -1.0;
        double cpuSystem = -1.0;
        long   osTotalMb = -1L;
        long   osFreeMb  = -1L;
        try {
            com.sun.management.OperatingSystemMXBean osMX =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            cpuLoad   = osMX.getProcessCpuLoad();
            cpuSystem = osMX.getCpuLoad();
            osTotalMb = osMX.getTotalMemorySize()  / 1_048_576L;
            osFreeMb  = osMX.getFreeMemorySize()   / 1_048_576L;
        } catch (ClassCastException ignored) {}

        return "{\n"
            + "  \"heap.used.mb\":"          + heap.getUsed()      / 1_048_576L + ",\n"
            + "  \"heap.max.mb\":"           + heap.getMax()       / 1_048_576L + ",\n"
            + "  \"heap.committed.mb\":"     + heap.getCommitted() / 1_048_576L + ",\n"
            + "  \"heap.usage.percent\":\""  + String.format("%.1f%%", heapPct) + "\",\n"
            + "  \"nonheap.used.mb\":"       + nonHeap.getUsed()   / 1_048_576L + ",\n"
            + "  \"metaspace.used.mb\":"     + metaspace                        + ",\n"
            + "  \"gc.total.count\":"        + gcCount                          + ",\n"
            + "  \"gc.total.time.ms\":"      + gcTime                           + ",\n"
            + "  \"thread.count\":"          + thrMX.getThreadCount()           + ",\n"
            + "  \"thread.daemon\":"         + thrMX.getDaemonThreadCount()     + ",\n"
            + "  \"thread.peak\":"           + thrMX.getPeakThreadCount()       + ",\n"
            + "  \"thread.blocked\":"        + blocked                          + ",\n"
            + "  \"thread.waiting\":"        + waiting                          + ",\n"
            + "  \"thread.timed_waiting\":"  + timed                            + ",\n"
            + "  \"thread.runnable\":"       + runnable                         + ",\n"
            + "  \"cpu.process\":\""         + String.format("%.1f%%", Math.max(0, cpuLoad   * 100)) + "\",\n"
            + "  \"cpu.system\":\""          + String.format("%.1f%%", Math.max(0, cpuSystem * 100)) + "\",\n"
            + "  \"os.total.memory.mb\":"    + osTotalMb                        + ",\n"
            + "  \"os.free.memory.mb\":"     + osFreeMb                         + ",\n"
            + "  \"uptime.ms\":"             + rtMX.getUptime()                 + ",\n"
            + "  \"jvm.name\":\""            + esc(rtMX.getVmName())            + "\",\n"
            + "  \"jvm.version\":\""         + esc(rtMX.getVmVersion())         + "\"\n"
            + "}";
    }

    @SuppressWarnings("unchecked")
    String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(','); first = false;
            sb.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof String)  sb.append('"').append(esc(v.toString())).append('"');
            else if (v instanceof List<?> list) sb.append(listLiteralJson(list));
            else sb.append(v);
        }
        return sb.append('}').toString();
    }

    private String listToJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append("  ").append(mapToJson(list.get(i)));
            if (i < list.size() - 1) sb.append(',');
            sb.append('\n');
        }
        return sb.append(']').toString();
    }

    private String listLiteralJson(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            Object item = list.get(i);
            if (item instanceof String) sb.append('"').append(esc(item.toString())).append('"');
            else sb.append(item);
        }
        return sb.append(']').toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    @FunctionalInterface
    private interface ResponseSupplier { String get() throws Exception; }
}
