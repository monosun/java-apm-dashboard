package com.monosun.monitor.remote;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 원격 JVM에서 실행 중인 ThreadMonitorAgent HTTP 서버에 연결해 데이터를 수집합니다.
 * JMX 없이 순수 HTTP로 스레드 정보를 가져오는 대안 수집기입니다.
 *
 * 대상 JVM 준비:
 *   java -javaagent:java-monitor-agent-1.3.0.jar=port=7979 -jar your-app.jar
 *
 * 설정 (monitor.properties):
 *   agent.enabled=true
 *   agent.host=target-host
 *   agent.port=7979
 *   agent.poll.interval.sec=5
 */
public class AgentHttpClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AgentHttpClient.class.getName());

    private final String host;
    private final int    port;
    private final String baseUrl;
    private final int    connectTimeoutMs;
    private final int    readTimeoutMs;

    private volatile boolean               connected       = false;
    private volatile long                  lastCollectedAt = 0;
    private volatile String                lastError       = "";
    private volatile String                jvmJson         = "{}";
    private volatile String                threadsJson     = "[]";
    private volatile String                infoJson        = "{}";

    public AgentHttpClient(String host, int port) {
        this.host            = host;
        this.port            = port;
        this.baseUrl         = "http://" + host + ":" + port;
        this.connectTimeoutMs = 3000;
        this.readTimeoutMs    = 5000;
    }

    // ── 연결 확인 ─────────────────────────────────────────────────────────────

    public boolean connect() {
        try {
            String result = get("/agent/health");
            connected    = result.contains("UP");
            lastError    = "";
            if (connected) {
                infoJson = get("/agent/info");
                collect();
                LOG.info("[AgentHttpClient] 연결 성공: " + host + ":" + port);
            }
            return connected;
        } catch (Exception e) {
            lastError = e.getMessage();
            connected = false;
            LOG.warning("[AgentHttpClient] 연결 실패 (" + host + ":" + port + "): " + e.getMessage());
            return false;
        }
    }

    // ── 데이터 수집 ────────────────────────────────────────────────────────────

    public void collect() {
        if (!connected) return;
        try {
            jvmJson         = get("/agent/jvm");
            threadsJson     = get("/agent/threads");
            lastCollectedAt = System.currentTimeMillis();
        } catch (Exception e) {
            lastError = e.getMessage();
            connected = false;
            LOG.warning("[AgentHttpClient] 수집 오류 — 연결 해제: " + e.getMessage());
        }
    }

    // ── 스케줄 폴링 ───────────────────────────────────────────────────────────

    public void startPolling(int pollSec, ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(() -> {
            if (connected) {
                collect();
            } else {
                LOG.info("[AgentHttpClient] 재연결 시도: " + host + ":" + port);
                connect();
            }
        }, pollSec, pollSec, TimeUnit.SECONDS);
    }

    // ── 개별 엔드포인트 조회 ───────────────────────────────────────────────────

    /** 단일 스레드 상세 (전체 스택 트레이스) */
    public String getThreadDetail(long threadId) {
        if (!connected) return "{\"error\":\"not connected\"}";
        try { return get("/agent/thread/" + threadId); }
        catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    /** 전체 스레드 덤프 (text/plain, jstack 형식) */
    public String getThreadDump() {
        if (!connected) return "Agent not connected to " + host + ":" + port;
        try { return get("/agent/threaddump"); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    /** HTTP 요청 처리기 목록 (Tomcat RequestProcessor, JSON 문자열) */
    public String getRequestProcessorsJson() {
        if (!connected) return "[]";
        try { return get("/agent/requests"); }
        catch (Exception e) { return "[]"; }
    }

    /** DB 커넥션 풀 목록 (JSON 문자열) */
    public String getConnectionPoolsJson() {
        if (!connected) return "[]";
        try { return get("/agent/dbpools"); }
        catch (Exception e) { return "[]"; }
    }

    /** 데드락 정보 (JSON 문자열) */
    public String getDeadlocksJson() {
        if (!connected) return "[]";
        try { return get("/agent/deadlocks"); }
        catch (Exception e) { return "[]"; }
    }

    // ── 캐시된 데이터 접근자 ──────────────────────────────────────────────────

    public String getJvmJson()      { return jvmJson; }
    public String getThreadsJson()  { return threadsJson; }
    public String getInfoJson()     { return infoJson; }
    public boolean isConnected()    { return connected; }
    public String  getTarget()      { return host + ":" + port; }
    public long    getLastCollectedAt() { return lastCollectedAt; }

    public String statusJson() {
        long age = lastCollectedAt > 0 ? System.currentTimeMillis() - lastCollectedAt : -1;
        return String.format(
            "{\"connected\":%b,\"target\":\"%s:%d\",\"lastCollectedMs\":%d,\"error\":\"%s\"}",
            connected, host, port, age, lastError.replace("\"", "'"));
    }

    @Override
    public void close() { connected = false; }

    // ── HTTP GET 헬퍼 ────────────────────────────────────────────────────────

    private String get(String path) throws IOException {
        URL url = URI.create(baseUrl + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("Accept", "*/*");
        int status = conn.getResponseCode();
        if (status >= 400) throw new IOException("HTTP " + status + " for " + path);
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
