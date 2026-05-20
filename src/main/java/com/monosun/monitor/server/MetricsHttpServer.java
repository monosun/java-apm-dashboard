package com.monosun.monitor.server;

import com.monosun.monitor.core.JvmMetricsCollector;
import com.monosun.monitor.core.Span;
import com.monosun.monitor.core.SpanStorage;
import com.monosun.monitor.exporter.PrometheusExporter;
import com.monosun.monitor.remote.RemoteJvmCollector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * JDK 내장 HttpServer를 사용하는 경량 메트릭 HTTP 서버.
 * 외부 라이브러리 불필요. Java 11+ 에서 동작합니다.
 *
 * 엔드포인트:
 *   GET /dashboard — 실시간 그래프 대시보드 (HTML)
 *   GET /dashboard      — 실시간 그래프 대시보드 (HTML)
 *   GET /metrics        — Prometheus 텍스트 형식
 *   GET /health         — 간단한 헬스 체크
 *   GET /jvm            — 로컬 JVM 메트릭 JSON
 *   GET /traces         — 최근 스팬 JSON
 *   GET /api/stats      — 스팬 통계 JSON
 *   GET /remote/jvm     — 원격 JVM 메트릭 JSON (remote.jmx.enabled=true 시)
 *   GET /remote/status  — 원격 JVM 연결 상태 JSON
 */
public class MetricsHttpServer {

    private final HttpServer        server;
    private final int               port;
    private final String            dashboardHtml;
    private final RemoteJvmCollector remoteCollector;

    public MetricsHttpServer(int port,
                             PrometheusExporter exporter,
                             JvmMetricsCollector jvm,
                             SpanStorage storage,
                             RemoteJvmCollector remoteCollector) throws IOException {
        this.port            = port;
        this.dashboardHtml   = loadResource("dashboard.html");
        this.remoteCollector = remoteCollector;
        this.server          = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/dashboard", exchange -> handle(exchange, "text/html; charset=UTF-8",
            () -> dashboardHtml));

        server.createContext("/metrics", exchange -> handle(exchange, "text/plain; version=0.0.4",
            exporter::export));

        server.createContext("/health", exchange -> handle(exchange, "application/json",
            () -> "{\"status\":\"UP\",\"uptime_ms\":" + jvm.uptimeMs() + "}"));

        server.createContext("/jvm", exchange -> handle(exchange, "application/json",
            () -> toJson(jvm.snapshot())));

        server.createContext("/traces", exchange -> handle(exchange, "application/json",
            () -> toSpanJson(storage.getRecentSpans(50))));

        server.createContext("/api/stats", exchange -> handle(exchange, "application/json",
            () -> toStatsJson(storage)));

        server.createContext("/remote/jvm", exchange -> handle(exchange, "application/json",
            () -> remoteCollector != null
                ? toJson(remoteCollector.getSnapshot())
                : "{\"error\":\"remote JMX 미설정 (monitor.properties: remote.jmx.enabled=true)\"}"));

        server.createContext("/remote/status", exchange -> handle(exchange, "application/json",
            () -> remoteCollector != null
                ? remoteCollector.statusJson()
                : "{\"connected\":false,\"target\":\"\",\"lastCollectedMs\":-1,\"error\":\"미설정\"}"));

        server.createContext("/threads", exchange -> handle(exchange, "application/json",
            () -> toThreadListJson(jvm.getThreadList())));

        server.createContext("/api/threaddump", exchange -> handle(exchange, "text/plain; charset=UTF-8",
            jvm::getThreadDump));

        server.createContext("/remote/threads", exchange -> handle(exchange, "application/json",
            () -> remoteCollector != null && remoteCollector.isConnected()
                ? toThreadListJson(remoteCollector.getThreadList())
                : "[]"));

        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "metrics-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() { server.start(); }
    public void stop()  { server.stop(0); }
    public int getPort(){ return port; }

    // ── 핸들러 ────────────────────────────────────────────────────────────────

    private void handle(HttpExchange ex, String contentType, ResponseSupplier supplier) {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                respond(ex, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = supplier.get();
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            respond(ex, 200, contentType, body);
        } catch (Exception e) {
            try { respond(ex, 500, "text/plain", "Error: " + e.getMessage()); }
            catch (IOException ignored) {}
        }
    }

    private void respond(HttpExchange ex, int status, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ── JSON 변환 (라이브러리 없이 직접 구현) ───────────────────────────────────

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{\n");
        map.forEach((k, v) -> sb.append("  \"").append(k).append("\": ")
            .append(v instanceof String ? "\"" + v + "\"" : v)
            .append(",\n"));
        if (sb.charAt(sb.length() - 2) == ',') sb.deleteCharAt(sb.length() - 2);
        return sb.append("}").toString();
    }

    private String toSpanJson(List<Span> spans) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < spans.size(); i++) {
            Span s = spans.get(i);
            sb.append("  {")
              .append("\"spanId\":\"").append(s.getSpanId()).append("\",")
              .append("\"traceId\":\"").append(s.getTraceId()).append("\",")
              .append("\"parentSpanId\":").append(s.getParentSpanId() != null
                  ? "\"" + s.getParentSpanId() + "\"" : "null").append(",")
              .append("\"operation\":\"").append(escape(s.getOperationName())).append("\",")
              .append("\"startTime\":\"").append(s.getStartInstant()).append("\",")
              .append("\"durationMs\":").append(s.getDurationMillis()).append(",")
              .append("\"status\":\"").append(s.getStatus()).append("\"")
              .append(s.getErrorMessage() != null
                  ? ",\"error\":\"" + escape(s.getErrorMessage()) + "\"" : "")
              .append(tagsJson(s))
              .append("}");
            if (i < spans.size() - 1) sb.append(",");
            sb.append("\n");
        }
        return sb.append("]").toString();
    }

    private String tagsJson(Span span) {
        if (span.getTags().isEmpty()) return "";
        StringBuilder sb = new StringBuilder(",\"tags\":{");
        span.getTags().forEach((k, v) ->
            sb.append("\"").append(escape(k)).append("\":\"").append(escape(v)).append("\","));
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        return sb.append("}").toString();
    }

    private String toThreadListJson(List<Map<String, Object>> threads) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < threads.size(); i++) {
            sb.append("  {");
            Map<String, Object> t = threads.get(i);
            List<String> keys = new ArrayList<>(t.keySet());
            for (int j = 0; j < keys.size(); j++) {
                String k = keys.get(j);
                Object v = t.get(k);
                sb.append("\"").append(k).append("\":");
                if (v instanceof String) {
                    sb.append("\"").append(escape(v.toString())).append("\"");
                } else {
                    sb.append(v); // Long, Boolean, Integer
                }
                if (j < keys.size() - 1) sb.append(",");
            }
            sb.append("}");
            if (i < threads.size() - 1) sb.append(",");
            sb.append("\n");
        }
        return sb.append("]").toString();
    }

    private String toStatsJson(SpanStorage storage) {
        return String.format(
            "{\"totalSpans\":%d,\"errorSpans\":%d,\"avgDurationMs\":%.1f,\"errorRate\":%.1f}",
            storage.getTotalSpans(),
            storage.getErrorSpans(),
            storage.getAverageDurationMillis(),
            storage.getErrorRate());
    }

    private static String loadResource(String name) {
        try (InputStream is = MetricsHttpServer.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                return "<html><body><h2>Dashboard not found: " + name + "</h2></body></html>";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<html><body><h2>Error loading dashboard: " + e.getMessage() + "</h2></body></html>";
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface ResponseSupplier {
        String get() throws Exception;
    }
}
