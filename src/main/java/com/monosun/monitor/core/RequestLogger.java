package com.monosun.monitor.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * HTTP 요청 처리 내역을 logs/http-requests.log 파일에 기록합니다.
 *
 * 동일 요청(worker + requestCount)은 한 번만 기록합니다.
 * HTTP 요청 헤더는 Tomcat RequestProcessor MBean이 노출하지 않으므로
 * 포함되지 않습니다. method/URI/queryString/IP/contentType 등은 기록됩니다.
 *
 * 로그 형식 (파이프 구분):
 *   timestamp | src=<jmx|agent> | thread=<이름> | method=<GET|POST…> |
 *   uri=<path>?<qs> | ip=<remoteAddr> | vhost=<virtualHost> |
 *   ct=<contentType> | stage=<N> | procMs=<ms> | reqCount=<N> |
 *   errors=<N> | bytesSent=<N> | bytesRecv=<N>
 */
public class RequestLogger {

    private static final Logger LOG;

    static {
        Logger l = Logger.getLogger("com.monosun.monitor.http.requests");
        l.setUseParentHandlers(false);
        try {
            Files.createDirectories(Paths.get("logs"));
            FileHandler fh = new FileHandler("logs/http-requests%u.log",
                                              10 * 1024 * 1024, 5, true);
            fh.setFormatter(new java.util.logging.Formatter() {
                @Override
                public String format(LogRecord r) { return r.getMessage() + "\n"; }
            });
            l.addHandler(fh);
        } catch (IOException e) {
            Logger.getLogger(RequestLogger.class.getName())
                  .warning("[RequestLogger] 로그 파일 초기화 실패: " + e.getMessage());
        }
        LOG = l;
    }

    // key: "source:worker", value: last logged requestCount
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final String source;

    public RequestLogger(String source) {
        this.source = source;
    }

    /**
     * 새로운 활성 요청이 감지되면 로그에 기록합니다.
     * stage &gt; 0 이고 currentUri 가 있는 요청만 기록 대상입니다.
     */
    public void logIfNew(List<Map<String, Object>> requests) {
        if (requests == null || requests.isEmpty()) return;
        for (Map<String, Object> r : requests) {
            String worker = String.valueOf(r.getOrDefault("worker", ""));
            String uri    = String.valueOf(r.getOrDefault("currentUri", ""));
            if (worker.isEmpty() || uri.isEmpty()) continue;

            int stage = r.get("stage") instanceof Number
                ? ((Number) r.get("stage")).intValue() : -1;
            if (stage <= 0) continue;

            long reqCount = r.get("requestCount") instanceof Number
                ? ((Number) r.get("requestCount")).longValue() : 0L;

            String key  = source + ":" + worker;
            Long   prev = lastSeen.get(key);
            if (prev != null && prev == reqCount) continue;
            lastSeen.put(key, reqCount);

            LOG.info(buildEntry(r));
        }
    }

    private String buildEntry(Map<String, Object> r) {
        String qs = String.valueOf(r.getOrDefault("queryString", ""));
        String fullUri = r.getOrDefault("currentUri", "") + (qs.isEmpty() ? "" : "?" + qs);
        return String.join(" | ",
            Instant.now().toString(),
            "src="        + source,
            "thread="     + r.getOrDefault("worker",       ""),
            "method="     + r.getOrDefault("method",       ""),
            "uri="        + fullUri,
            "ip="         + r.getOrDefault("remoteAddr",   ""),
            "vhost="      + r.getOrDefault("virtualHost",  ""),
            "ct="         + r.getOrDefault("contentType",  ""),
            "stage="      + r.getOrDefault("stage",         ""),
            "procMs="     + r.getOrDefault("processingTimeMs", 0),
            "reqCount="   + r.getOrDefault("requestCount", 0),
            "errors="     + r.getOrDefault("errorCount",   0),
            "bytesSent="  + r.getOrDefault("bytesSent",    0),
            "bytesRecv="  + r.getOrDefault("bytesReceived", 0)
        );
    }
}
