package com.monosun.monitor.agent;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * 원격 JVM에 부착하는 스레드 모니터링 에이전트.
 *
 * 사용법 (정적 부착):
 *   java -javaagent:java-monitor-agent-1.3.0.jar=port=7979 -jar your-app.jar
 *
 * 사용법 (동적 부착 - Attach API):
 *   VirtualMachine vm = VirtualMachine.attach("PID");
 *   vm.loadAgent("/path/to/java-monitor-agent-1.3.0.jar", "port=7979");
 *   vm.detach();
 *
 * 에이전트 시작 후 엔드포인트:
 *   http://host:7979/agent/threads      — 스레드 목록 (JSON)
 *   http://host:7979/agent/thread/{id}  — 스레드 상세 + 전체 스택 (JSON)
 *   http://host:7979/agent/threaddump   — 전체 스레드 덤프 (text/plain)
 *   http://host:7979/agent/jvm          — JVM 메트릭 (JSON)
 *   http://host:7979/agent/deadlocks    — 데드락 감지 (JSON)
 *   http://host:7979/agent/info         — 에이전트 메타정보 (JSON)
 *   http://host:7979/agent/health       — 헬스 체크 (JSON)
 *
 * monitor.properties 에서 에이전트 연결:
 *   agent.enabled=true
 *   agent.host=target-host
 *   agent.port=7979
 */
public class ThreadMonitorAgent {

    private static final Logger LOG = Logger.getLogger(ThreadMonitorAgent.class.getName());

    private static volatile AgentHttpServer httpServer;

    /** 정적 부착: JVM 시작 시 -javaagent 옵션으로 자동 호출 */
    public static void premain(String args, Instrumentation inst) {
        start(args);
    }

    /** 동적 부착: 실행 중인 JVM에 Attach API로 로드 시 호출 */
    public static void agentmain(String args, Instrumentation inst) {
        start(args);
    }

    private static synchronized void start(String args) {
        if (httpServer != null) {
            LOG.warning("[ThreadMonitorAgent] 이미 실행 중 (port=" + httpServer.getPort() + ")");
            return;
        }
        int port = parsePort(args, 7979);
        try {
            httpServer = new AgentHttpServer(port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("[ThreadMonitorAgent] 종료");
                httpServer.stop();
            }, "agent-shutdown"));
            httpServer.start();
        } catch (Exception e) {
            LOG.severe("[ThreadMonitorAgent] 시작 실패: " + e.getMessage());
        }
    }

    /** "port=7979,key=val" 형식에서 port 파싱 */
    private static int parsePort(String args, int defaultPort) {
        if (args == null || args.isBlank()) return defaultPort;
        for (String part : args.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "port".equalsIgnoreCase(kv[0].trim())) {
                try { return Integer.parseInt(kv[1].trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        return defaultPort;
    }
}
