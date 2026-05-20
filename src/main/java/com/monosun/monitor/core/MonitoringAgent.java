package com.monosun.monitor.core;

import com.monosun.monitor.config.MonitorConfig;
import com.monosun.monitor.exporter.PrometheusExporter;
import com.monosun.monitor.remote.AgentHttpClient;
import com.monosun.monitor.remote.RemoteJvmCollector;
import com.monosun.monitor.server.MetricsHttpServer;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 모니터링 에이전트의 진입점 (싱글톤).
 *
 * 초기화 예:
 *   MonitoringAgent.builder()
 *       .httpPort(9090)
 *       .printInterval(30)
 *       .build()
 *       .start();
 */
public class MonitoringAgent {

    private static final Logger LOG = Logger.getLogger(MonitoringAgent.class.getName());

    private final JvmMetricsCollector  jvmCollector;
    private final TransactionTracer    tracer;
    private final SpanStorage          spanStorage;
    private final MetricsHttpServer    httpServer;
    private final ScheduledExecutorService scheduler;
    private final int                  printIntervalSec;
    private final RemoteJvmCollector   remoteCollector;
    private final AgentHttpClient      agentClient;

    private MonitoringAgent(Builder builder) throws IOException {
        MonitorConfig cfg      = builder.config != null ? builder.config : MonitorConfig.load();
        this.spanStorage       = new SpanStorage(builder.spanBufferSize > 0 ? builder.spanBufferSize : cfg.spanBufferSize);
        this.jvmCollector      = new JvmMetricsCollector();
        this.printIntervalSec  = builder.printIntervalSec >= 0 ? builder.printIntervalSec : cfg.printIntervalSec;

        TransactionTracer.init(spanStorage);
        this.tracer = TransactionTracer.getInstance();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 원격 JMX 수집기
        RemoteJvmCollector remote = null;
        if (cfg.remoteEnabled) {
            remote = new RemoteJvmCollector(cfg.remoteHost, cfg.remotePort, cfg.remoteUser, cfg.remotePassword);
            remote.connect();
            remote.startPolling(cfg.remotePollIntervalSec, cfg.remoteReconnectIntervalSec, scheduler);
        }
        this.remoteCollector = remote;

        // Agent HTTP 클라이언트
        AgentHttpClient agent = null;
        if (cfg.agentEnabled) {
            agent = new AgentHttpClient(cfg.agentHost, cfg.agentPort);
            agent.connect();
            agent.startPolling(cfg.agentPollIntervalSec, scheduler);
        }
        this.agentClient = agent;

        int port = builder.httpPort > 0 ? builder.httpPort : cfg.httpPort;
        PrometheusExporter exporter = new PrometheusExporter(jvmCollector, spanStorage);
        this.httpServer = new MetricsHttpServer(port, exporter, jvmCollector, spanStorage,
            remoteCollector, agentClient, cfg.tracesEnabled);
    }

    // -------------------------------------------------------------------------
    // 생명주기
    // -------------------------------------------------------------------------

    public MonitoringAgent start() throws IOException {
        httpServer.start();
        if (printIntervalSec > 0) {
            scheduler.scheduleAtFixedRate(this::printJvmSnapshot, 0, printIntervalSec, TimeUnit.SECONDS);
        }
        LOG.info("[MonitoringAgent] 시작 완료. 메트릭 서버: http://localhost:"
            + httpServer.getPort() + "/metrics");
        return this;
    }

    public void stop() {
        scheduler.shutdownNow();
        httpServer.stop();
        if (remoteCollector != null) remoteCollector.close();
        if (agentClient    != null) agentClient.close();
        LOG.info("[MonitoringAgent] 종료");
    }

    // -------------------------------------------------------------------------
    // 공개 API
    // -------------------------------------------------------------------------

    public TransactionTracer    tracer()          { return tracer; }
    public JvmMetricsCollector  jvmCollector()    { return jvmCollector; }
    public SpanStorage          spanStorage()     { return spanStorage; }
    public RemoteJvmCollector   remoteCollector() { return remoteCollector; }
    public AgentHttpClient      agentClient()     { return agentClient; }

    public void printJvmSnapshot() {
        Map<String, Object> snap = jvmCollector.snapshot();
        StringBuilder sb = new StringBuilder("\n===== JVM Snapshot =====\n");
        snap.forEach((k, v) -> sb.append(String.format("  %-28s %s%n", k, v)));
        long[] deadlocked = jvmCollector.findDeadlockedThreads();
        if (deadlocked.length > 0) {
            sb.append("  [경고] 데드락 감지! 스레드 ID: ");
            for (long id : deadlocked) sb.append(id).append(' ');
            sb.append('\n');
        }
        SpanStorage storage = tracer.getStorage();
        sb.append(String.format("  %-28s %d%n", "spans.total",   storage.getTotalSpans()));
        sb.append(String.format("  %-28s %.1f%%%n", "spans.error.rate", storage.getErrorRate()));
        sb.append(String.format("  %-28s %.2fms%n", "spans.avg.duration", storage.getAverageDurationMillis()));
        sb.append("========================");
        LOG.info(sb.toString());
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private MonitorConfig config          = null;
        private int           httpPort        = 0;   // 0 = config 값 사용
        private int           printIntervalSec = -1; // -1 = config 값 사용
        private int           spanBufferSize  = 0;   // 0 = config 값 사용

        /** 설정 파일 객체 직접 지정 */
        public Builder config(MonitorConfig cfg)        { this.config = cfg; return this; }
        /** 포트 직접 지정 (config보다 우선) */
        public Builder httpPort(int port)               { this.httpPort = port; return this; }
        /** 콘솔 출력 주기 직접 지정 */
        public Builder printInterval(int seconds)       { this.printIntervalSec = seconds; return this; }
        /** 스팬 버퍼 크기 직접 지정 */
        public Builder spanBufferSize(int size)         { this.spanBufferSize = size; return this; }
        public Builder disableConsolePrint()            { this.printIntervalSec = 0; return this; }

        public MonitoringAgent build() throws IOException {
            return new MonitoringAgent(this);
        }
    }
}
