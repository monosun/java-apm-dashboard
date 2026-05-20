package com.monosun.monitor.config;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * monitor.properties 기반 설정 로더.
 *
 * 적용 우선순위 (낮 → 높):
 *   1. 클래스패스 내 monitor.properties (JAR 내 기본값)
 *   2. 실행 디렉터리의 monitor.properties (외부 파일 덮어쓰기)
 *   3. -Dserver.xxx / -Dremote.xxx / -Dalert.xxx 시스템 속성
 */
public final class MonitorConfig {

    private static final Logger LOG           = Logger.getLogger(MonitorConfig.class.getName());
    private static final String RESOURCE_NAME = "monitor.properties";

    // ── HTTP 서버 ────────────────────────────────────────────────────────────
    public final int     httpPort;
    public final int     printIntervalSec;
    public final int     spanBufferSize;
    public final boolean tracesEnabled;

    // ── 원격 JMX ────────────────────────────────────────────────────────────
    public final boolean remoteEnabled;
    public final String  remoteHost;
    public final int     remotePort;
    public final String  remoteUser;
    public final String  remotePassword;
    public final int     remotePollIntervalSec;
    public final int     remoteReconnectIntervalSec;

    // ── Agent HTTP 연결 ──────────────────────────────────────────────────────
    public final boolean agentEnabled;
    public final String  agentHost;
    public final int     agentPort;
    public final int     agentPollIntervalSec;

    // ── 경고 임계치 ──────────────────────────────────────────────────────────
    public final double heapAlertPercent;
    public final double cpuAlertPercent;
    public final double errorRateAlertPercent;

    // ── Factory ──────────────────────────────────────────────────────────────

    /** 기본 로드: 클래스패스 → 현재 디렉터리 → 시스템 속성 */
    public static MonitorConfig load() {
        return loadFrom(null);
    }

    /**
     * 특정 경로 로드. externalPath 가 null 이면 실행 디렉터리의 monitor.properties 를 탐색합니다.
     */
    public static MonitorConfig loadFrom(String externalPath) {
        Properties p = new Properties();

        // 1. classpath default
        try (InputStream is = MonitorConfig.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (is != null) {
                p.load(is);
                LOG.fine("[MonitorConfig] 클래스패스 기본값 로드 완료");
            }
        } catch (IOException e) {
            LOG.warning("[MonitorConfig] 기본값 로드 실패: " + e.getMessage());
        }

        // 2. external file override
        Path ext = (externalPath != null) ? Paths.get(externalPath) : Paths.get(RESOURCE_NAME);
        if (Files.exists(ext)) {
            try (InputStream is = Files.newInputStream(ext)) {
                p.load(is);
                LOG.info("[MonitorConfig] 외부 설정 파일 로드: " + ext.toAbsolutePath());
            } catch (IOException e) {
                LOG.warning("[MonitorConfig] 외부 설정 파일 로드 실패: " + e.getMessage());
            }
        }

        // 3. system property overrides
        System.getProperties().stringPropertyNames().stream()
            .filter(k -> k.startsWith("server.") || k.startsWith("remote.") || k.startsWith("alert."))
            .forEach(k -> p.setProperty(k, System.getProperty(k)));

        return new MonitorConfig(p);
    }

    // ── 생성자 ────────────────────────────────────────────────────────────────

    private MonitorConfig(Properties p) {
        this.httpPort             = i(p, "server.http.port",                    9090);
        this.printIntervalSec     = i(p, "server.print.interval.sec",           15);
        this.spanBufferSize       = i(p, "server.span.buffer.size",             1000);
        this.tracesEnabled        = b(p, "server.traces.enabled",               true);

        this.remoteEnabled              = b(p, "remote.jmx.enabled",                  false);
        this.remoteHost                 = s(p, "remote.jmx.host",                     "localhost");
        this.remotePort                 = i(p, "remote.jmx.port",                     9999);
        this.remoteUser                 = s(p, "remote.jmx.user",                     "");
        this.remotePassword             = s(p, "remote.jmx.password",                 "");
        this.remotePollIntervalSec      = i(p, "remote.jmx.poll.interval.sec",        5);
        this.remoteReconnectIntervalSec = i(p, "remote.jmx.reconnect.interval.sec",   30);

        this.agentEnabled            = b(p, "agent.enabled",          false);
        this.agentHost               = s(p, "agent.host",             "localhost");
        this.agentPort               = i(p, "agent.port",             7979);
        this.agentPollIntervalSec    = i(p, "agent.poll.interval.sec", 5);

        this.heapAlertPercent      = d(p, "alert.heap.percent",        85.0);
        this.cpuAlertPercent       = d(p, "alert.cpu.percent",         80.0);
        this.errorRateAlertPercent = d(p, "alert.error.rate.percent",  10.0);

        LOG.info(summary());
    }

    public String summary() {
        return String.format(
            "[MonitorConfig] port=%d printInterval=%ds buffer=%d remote=%s%s agent=%s%s",
            httpPort, printIntervalSec, spanBufferSize,
            remoteEnabled ? "ON" : "OFF",
            remoteEnabled ? " target=" + remoteHost + ":" + remotePort : "",
            agentEnabled ? "ON" : "OFF",
            agentEnabled ? " target=" + agentHost + ":" + agentPort : "");
    }

    // ── 타입 변환 헬퍼 ───────────────────────────────────────────────────────

    private static int i(Properties p, String k, int def) {
        String v = p.getProperty(k);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double d(Properties p, String k, double def) {
        String v = p.getProperty(k);
        if (v == null || v.isBlank()) return def;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean b(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v != null ? "true".equalsIgnoreCase(v.trim()) : def;
    }

    private static String s(Properties p, String k, String def) {
        String v = p.getProperty(k);
        return v != null ? v.trim() : def;
    }
}
