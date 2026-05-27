package com.monosun.monitor.agent;

import com.monosun.monitor.core.RequestLogger;
import javax.management.*;
import java.lang.management.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * 대상 JVM에서 스레드 상세 정보를 수집합니다.
 * ThreadMXBean을 통해 전체 스택 트레이스, 잠금 정보, CPU/대기 시간 등을 수집합니다.
 */
public class AgentThreadCollector {

    private static final Logger LOG = Logger.getLogger(AgentThreadCollector.class.getName());
    private static final String TRACE_REGISTRY_MBEAN = "com.monosun.monitor:type=TraceRegistry";

    private final ThreadMXBean  threadMX      = ManagementFactory.getThreadMXBean();
    private final RequestLogger requestLogger = new RequestLogger("agent");

    public AgentThreadCollector() {
        if (threadMX.isThreadContentionMonitoringSupported()) {
            threadMX.setThreadContentionMonitoringEnabled(true);
        }
        if (threadMX.isThreadCpuTimeSupported()) {
            threadMX.setThreadCpuTimeEnabled(true);
        }
    }

    /** 스레드 목록 (maxFrames 프레임 스택 포함, BLOCKED→WAITING→RUNNABLE 정렬) */
    public List<Map<String, Object>> getThreadList(int maxFrames) {
        long[] ids = threadMX.getAllThreadIds();
        if (ids.length > 500) ids = Arrays.copyOf(ids, 500);
        ThreadInfo[] infos = threadMX.getThreadInfo(ids, maxFrames);
        boolean cpuOk    = threadMX.isThreadCpuTimeSupported() && threadMX.isThreadCpuTimeEnabled();
        boolean timingOk = threadMX.isThreadContentionMonitoringSupported()
                        && threadMX.isThreadContentionMonitoringEnabled();
        Map<Long, String> traceMap = readLocalTraceRegistry();

        List<Map<String, Object>> result = new ArrayList<>();
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            Map<String, Object> m = toMap(ti, cpuOk, timingOk, maxFrames);
            String tid = traceMap.get(ti.getThreadId());
            if (tid != null) m.put("traceId", tid);
            result.add(m);
        }
        result.sort((a, b) -> {
            int oa = stateOrder(a.get("state").toString());
            int ob = stateOrder(b.get("state").toString());
            if (oa != ob) return oa - ob;
            long ca = a.containsKey("cpuMs") ? (Long) a.get("cpuMs") : 0L;
            long cb = b.containsKey("cpuMs") ? (Long) b.get("cpuMs") : 0L;
            return Long.compare(cb, ca);
        });
        return result;
    }

    /** 특정 스레드 전체 상세 (전체 스택 + 잠금 체인) */
    public Map<String, Object> getThreadDetail(long id) {
        ThreadInfo[] infos = threadMX.getThreadInfo(new long[]{id}, Integer.MAX_VALUE);
        if (infos.length == 0 || infos[0] == null) return null;
        boolean cpuOk    = threadMX.isThreadCpuTimeSupported() && threadMX.isThreadCpuTimeEnabled();
        boolean timingOk = threadMX.isThreadContentionMonitoringSupported()
                        && threadMX.isThreadContentionMonitoringEnabled();
        Map<String, Object> m = toMap(infos[0], cpuOk, timingOk, Integer.MAX_VALUE);
        String traceId = readLocalTraceRegistry().get(id);
        if (traceId != null) m.put("traceId", traceId);
        return m;
    }

    /** 전체 스레드 덤프 (jstack 형식) */
    public String getThreadDump() {
        ThreadInfo[] infos = threadMX.dumpAllThreads(true, true);
        long[] deadlocked = findDeadlocked();
        Set<Long> deadSet = new HashSet<>();
        for (long id : deadlocked) deadSet.add(id);

        StringBuilder sb = new StringBuilder();
        sb.append("Full Thread Dump — ").append(Instant.now()).append('\n');
        sb.append("pid=").append(ProcessHandle.current().pid())
          .append(" threads=").append(threadMX.getThreadCount())
          .append(" daemon=").append(threadMX.getDaemonThreadCount())
          .append(" peak=").append(threadMX.getPeakThreadCount()).append("\n\n");

        if (!deadSet.isEmpty()) {
            sb.append("!!! DEADLOCK DETECTED — Thread IDs: ");
            deadSet.forEach(id -> sb.append(id).append(' '));
            sb.append("!!!\n\n");
        }

        for (ThreadInfo ti : infos) {
            sb.append('"').append(ti.getThreadName()).append('"');
            if (ti.isDaemon()) sb.append(" daemon");
            sb.append(" #").append(ti.getThreadId());
            sb.append(" prio=").append(ti.getPriority());
            if (deadSet.contains(ti.getThreadId())) sb.append(" *** DEADLOCKED ***");
            sb.append('\n');
            sb.append("   java.lang.Thread.State: ").append(ti.getThreadState()).append('\n');
            if (ti.getLockName() != null) {
                sb.append("   - waiting on <").append(ti.getLockName()).append('>');
                if (ti.getLockOwnerName() != null)
                    sb.append(" owned by \"").append(ti.getLockOwnerName())
                      .append("\" id=").append(ti.getLockOwnerId());
                sb.append('\n');
            }

            StackTraceElement[] stack = ti.getStackTrace();
            MonitorInfo[]       mons  = ti.getLockedMonitors();
            Map<Integer, List<MonitorInfo>> monByDepth = new HashMap<>();
            for (MonitorInfo m : mons)
                monByDepth.computeIfAbsent(m.getLockedStackDepth(), k -> new ArrayList<>()).add(m);

            for (int i = 0; i < stack.length; i++) {
                sb.append("\tat ").append(stack[i]).append('\n');
                List<MonitorInfo> atFrame = monByDepth.get(i);
                if (atFrame != null)
                    for (MonitorInfo m : atFrame)
                        sb.append("\t- locked <").append(m.getClassName()).append('@')
                          .append(Integer.toHexString(m.getIdentityHashCode())).append(">\n");
            }

            LockInfo[] syncs = ti.getLockedSynchronizers();
            if (syncs.length > 0) {
                sb.append("   Locked synchronizers:\n");
                for (LockInfo li : syncs)
                    sb.append("   - ").append(li.getClassName()).append('@')
                      .append(Integer.toHexString(li.getIdentityHashCode())).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * CPU 처리시간 기준 상위 N 스레드 (전체 스택 트레이스 포함).
     * 경량 1차 조회로 순위를 정한 뒤, 상위 N에 대해서만 전체 스택을 적재합니다.
     */
    public List<Map<String, Object>> getTopByProcessingTime(int limit) {
        long[] ids = threadMX.getAllThreadIds();
        if (ids.length > 500) ids = Arrays.copyOf(ids, 500);
        boolean cpuOk    = threadMX.isThreadCpuTimeSupported() && threadMX.isThreadCpuTimeEnabled();
        boolean timingOk = threadMX.isThreadContentionMonitoringSupported()
                        && threadMX.isThreadContentionMonitoringEnabled();

        // 1단계: CPU 시간 기반 경량 순위 결정
        long[] cpuNs = new long[ids.length];
        if (cpuOk) {
            for (int i = 0; i < ids.length; i++) {
                long ns = threadMX.getThreadCpuTime(ids[i]);
                cpuNs[i] = ns >= 0 ? ns : 0L;
            }
        }
        Integer[] order = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(cpuNs[b], cpuNs[a]));

        // 2단계: 상위 N ID만 전체 스택으로 조회
        int n = Math.min(limit, ids.length);
        long[] topIds = new long[n];
        for (int i = 0; i < n; i++) topIds[i] = ids[order[i]];

        ThreadInfo[] infos = threadMX.getThreadInfo(topIds, Integer.MAX_VALUE);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            result.add(toMap(ti, cpuOk, timingOk, Integer.MAX_VALUE));
        }
        result.sort((a, b) -> {
            long ca = a.containsKey("cpuMs") ? (Long) a.get("cpuMs") : 0L;
            long cb = b.containsKey("cpuMs") ? (Long) b.get("cpuMs") : 0L;
            return Long.compare(cb, ca);
        });
        return result;
    }

    /** 데드락 스레드 ID 배열 */
    public long[] findDeadlocked() {
        long[] d = threadMX.findDeadlockedThreads();
        return d != null ? d : new long[0];
    }

    /** 데드락 체인 정보 (JSON용) */
    public List<Map<String, Object>> getDeadlockInfo() {
        long[] ids = findDeadlocked();
        if (ids.length == 0) return List.of();
        ThreadInfo[] infos = threadMX.getThreadInfo(ids, 10);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          ti.getThreadId());
            m.put("name",        ti.getThreadName());
            m.put("state",       ti.getThreadState().name());
            if (ti.getLockName()      != null) m.put("lockName",    ti.getLockName());
            if (ti.getLockOwnerName() != null) m.put("lockOwner",   ti.getLockOwnerName());
            m.put("lockOwnerId", ti.getLockOwnerId());
            List<String> frames = new ArrayList<>();
            for (StackTraceElement e : ti.getStackTrace()) frames.add(e.toString());
            m.put("stackTrace", frames);
            result.add(m);
        }
        return result;
    }

    // ── TraceRegistry 읽기 (로컬 MBeanServer 경유) ──────────────────────────────

    /** 플랫폼 MBeanServer 에서 TraceRegistry MBean 을 조회해 threadId→traceId 맵 반환 */
    Map<Long, String> readLocalTraceRegistry() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName  on  = new ObjectName(TRACE_REGISTRY_MBEAN);
            if (!mbs.isRegistered(on)) return Collections.emptyMap();
            String json = (String) mbs.getAttribute(on, "ActiveTraces");
            return parseTraceJson(json);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public static Map<Long, String> parseTraceJson(String json) {
        Map<Long, String> m = new HashMap<>();
        if (json == null || json.isBlank()) return m;
        // {"123":"abc","456":"def"}
        String inner = json.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}"))   inner = inner.substring(0, inner.length() - 1);
        if (inner.isBlank()) return m;
        for (String pair : inner.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length < 2) continue;
            try {
                long   tid = Long.parseLong(kv[0].replaceAll("\"", "").trim());
                String tid2 = kv[1].replaceAll("\"", "").trim();
                m.put(tid, tid2);
            } catch (NumberFormatException ignored) {}
        }
        return m;
    }

    // ── 내부 변환 ────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(ThreadInfo ti, boolean cpuOk, boolean timingOk, int maxFrames) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id",        ti.getThreadId());
        t.put("name",      ti.getThreadName());
        t.put("state",     ti.getThreadState().name());
        t.put("daemon",    ti.isDaemon());
        t.put("priority",  ti.getPriority());
        t.put("inNative",  ti.isInNative());
        t.put("suspended", ti.isSuspended());

        if (cpuOk) {
            long cpuNs  = threadMX.getThreadCpuTime(ti.getThreadId());
            long userNs = threadMX.getThreadUserTime(ti.getThreadId());
            if (cpuNs  >= 0) t.put("cpuMs",  cpuNs  / 1_000_000L);
            if (userNs >= 0) t.put("userMs", userNs / 1_000_000L);
        }
        if (timingOk) {
            t.put("blockedCount",  ti.getBlockedCount());
            t.put("blockedTimeMs", ti.getBlockedTime());
            t.put("waitedCount",   ti.getWaitedCount());
            t.put("waitedTimeMs",  ti.getWaitedTime());
        }

        if (ti.getLockName()      != null) t.put("lockName",    ti.getLockName());
        if (ti.getLockOwnerName() != null) t.put("lockOwner",   ti.getLockOwnerName());
        if (ti.getLockOwnerId()   >= 0)    t.put("lockOwnerId", ti.getLockOwnerId());

        MonitorInfo[] monitors = ti.getLockedMonitors();
        if (monitors.length > 0) {
            List<String> monList = new ArrayList<>();
            for (MonitorInfo m : monitors)
                monList.add(m.getClassName() + '@' + Integer.toHexString(m.getIdentityHashCode())
                    + " (depth=" + m.getLockedStackDepth() + ')');
            t.put("lockedMonitors", monList);
        }

        LockInfo[] syncs = ti.getLockedSynchronizers();
        if (syncs.length > 0) {
            List<String> syncList = new ArrayList<>();
            for (LockInfo li : syncs)
                syncList.add(li.getClassName() + '@' + Integer.toHexString(li.getIdentityHashCode()));
            t.put("lockedSynchronizers", syncList);
        }

        StackTraceElement[] stack = ti.getStackTrace();
        List<String> frames = new ArrayList<>();
        int limit = maxFrames == Integer.MAX_VALUE ? stack.length : Math.min(stack.length, maxFrames);
        for (int i = 0; i < limit; i++) frames.add(stack[i].toString());
        t.put("stackTrace",  frames);
        t.put("stackDepth",  stack.length);

        return t;
    }

    // ── HTTP 요청 처리기 (Tomcat) ────────────────────────────────────────────

    public List<Map<String, Object>> getRequestProcessors() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName pattern = new ObjectName("Catalina:type=RequestProcessor,*");
            Set<ObjectName> names = mbs.queryNames(pattern, null);

            // 스레드 이름 → traceId 역매핑 (ThreadId 대신 이름 기반)
            Map<Long,   String> traceByTid  = readLocalTraceRegistry();
            Map<String, String> traceByName = new HashMap<>();
            ThreadInfo[] allTi = ManagementFactory.getThreadMXBean()
                .getThreadInfo(ManagementFactory.getThreadMXBean().getAllThreadIds(), 0);
            for (ThreadInfo ti : allTi) {
                if (ti == null) continue;
                String tr = traceByTid.get(ti.getThreadId());
                if (tr != null) traceByName.put(ti.getThreadName(), tr);
            }

            for (ObjectName on : names) {
                try {
                    Map<String, Object> r = new LinkedHashMap<>();

                    // currentThreadName이 빈 문자열이면 ObjectName의 name 키로 폴백
                    String threadName = String.valueOf(safeAttr(mbs, on, "currentThreadName", ""));
                    if (threadName.isEmpty()) {
                        String nameKey = on.getKeyProperty("name");
                        if (nameKey != null) threadName = nameKey;
                    }
                    r.put("worker", threadName);

                    // traceId 연결
                    String traceId = traceByName.get(threadName);
                    if (traceId != null) r.put("traceId", traceId);

                    r.put("stage",        safeAttr(mbs, on, "stage", -1));
                    r.put("currentUri",   safeAttr(mbs, on, "currentUri", ""));
                    r.put("method",       safeAttr(mbs, on, "method", ""));
                    r.put("queryString",  safeAttr(mbs, on, "currentQueryString", ""));
                    r.put("contentType",  safeAttr(mbs, on, "contentType", ""));
                    r.put("virtualHost",  safeAttr(mbs, on, "virtualHost", ""));

                    // IPv6 루프백 정규화
                    String remoteAddr = String.valueOf(safeAttr(mbs, on, "remoteAddr", ""));
                    if ("0:0:0:0:0:0:0:1".equals(remoteAddr) || "::1".equals(remoteAddr))
                        remoteAddr = "127.0.0.1";
                    r.put("remoteAddr", remoteAddr);

                    Object pt = safeAttr(mbs, on, "processingTime", 0L);
                    r.put("processingTimeMs", pt instanceof Number ? ((Number)pt).longValue() : 0L);
                    r.put("requestCount",  safeAttr(mbs, on, "requestCount", 0L));
                    r.put("errorCount",    safeAttr(mbs, on, "errorCount", 0L));
                    r.put("bytesSent",     safeAttr(mbs, on, "bytesSent", 0L));
                    r.put("bytesReceived", safeAttr(mbs, on, "bytesReceived", 0L));
                    result.add(r);
                } catch (Exception ignored) {}
            }
            result.sort((a, b) -> {
                long ta = a.get("processingTimeMs") instanceof Number ? ((Number)a.get("processingTimeMs")).longValue() : 0;
                long tb = b.get("processingTimeMs") instanceof Number ? ((Number)b.get("processingTimeMs")).longValue() : 0;
                return Long.compare(tb, ta);
            });

            // 평균 처리시간 계산 (활성 요청만 포함)
            long activeCount = result.stream()
                .filter(r -> !String.valueOf(r.getOrDefault("currentUri", "")).isEmpty())
                .count();
            if (activeCount > 0) {
                long sumMs = result.stream()
                    .filter(r -> !String.valueOf(r.getOrDefault("currentUri", "")).isEmpty())
                    .mapToLong(r -> r.get("processingTimeMs") instanceof Number
                        ? ((Number) r.get("processingTimeMs")).longValue() : 0L)
                    .filter(v -> v >= 0)
                    .sum();
                result.forEach(r -> r.put("avgProcessingTimeMs", sumMs / activeCount));
            }

            requestLogger.logIfNew(result);
        } catch (Exception e) {
            // Tomcat 없는 환경에서는 빈 목록 반환
        }
        return result;
    }

    // ── DB 커넥션 풀 ────────────────────────────────────────────────────────

    public List<Map<String, Object>> getConnectionPools() {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        List<Map<String, Object>> result = new ArrayList<>();
        result.addAll(queryHikariPools(mbs));
        result.addAll(queryLocalPools(mbs, "Catalina:type=DataSource,*",                       "tomcat-jdbc"));
        result.addAll(queryLocalPools(mbs, "org.apache.commons.pool2:type=GenericObjectPool,*", "dbcp2"));
        return result;
    }

    // HikariCP MBean 패턴은 세 가지:
    //   type=PoolStats,name=<pool>   — Dropwizard metrics 통합 (runtime 수치 + config)
    //   type=Pool (<pool>)           — 내장 JMX HikariPoolMXBean (runtime 수치)
    //   type=PoolConfig (<pool>)     — 내장 JMX HikariConfigMXBean (설정값)
    // PoolStats 우선, 없으면 Pool+PoolConfig 조합으로 수집한다.
    private List<Map<String, Object>> queryHikariPools(MBeanServer mbs) {
        Set<ObjectName> all;
        try {
            all = mbs.queryNames(new ObjectName("com.zaxxer.hikari:*"), null);
        } catch (Exception e) {
            return Collections.emptyList();
        }

        Map<String, ObjectName> statsMap  = new LinkedHashMap<>();
        Map<String, ObjectName> poolMap   = new LinkedHashMap<>();
        Map<String, ObjectName> configMap = new LinkedHashMap<>();

        for (ObjectName on : all) {
            String t = on.getKeyProperty("type");
            if (t == null) continue;
            if (t.equals("PoolStats")) {
                // type=PoolStats,name=<pool> or type=PoolStats,pool=<pool>
                String name = on.getKeyProperty("name");
                if (name == null) name = on.getKeyProperty("pool");
                if (name != null) statsMap.put(name, on);
            } else if (t.startsWith("Pool (") && t.endsWith(")")) {
                poolMap.put(t.substring(6, t.length() - 1), on);
            } else if (t.startsWith("PoolConfig (") && t.endsWith(")")) {
                configMap.put(t.substring(12, t.length() - 1), on);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>(statsMap.keySet());

        for (Map.Entry<String, ObjectName> e : statsMap.entrySet()) {
            result.add(buildHikariEntry(mbs, e.getKey(), e.getValue(), null));
        }
        for (Map.Entry<String, ObjectName> e : poolMap.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            result.add(buildHikariEntry(mbs, e.getKey(), e.getValue(), configMap.get(e.getKey())));
        }
        return result;
    }

    private Map<String, Object> buildHikariEntry(MBeanServer mbs, String poolName,
                                                  ObjectName statsOn, ObjectName cfgOn) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("poolType", "hikari");
        p.put("poolName", poolName);
        p.put("totalConnections",  toLong(safeAttr(mbs, statsOn, "TotalConnections",         -1L)));
        p.put("activeConnections", toLong(safeAttr(mbs, statsOn, "ActiveConnections",         -1L)));
        p.put("idleConnections",   toLong(safeAttr(mbs, statsOn, "IdleConnections",           -1L)));
        p.put("pendingThreads",    toLong(safeAttr(mbs, statsOn, "ThreadsAwaitingConnection", -1L)));
        // MaximumPoolSize / MinimumIdle: Pool 빈에 없으면 PoolConfig 빈에서 읽는다
        p.put("maxPoolSize", toLong(safeAttr(mbs, statsOn, "MaximumPoolSize",
                             cfgOn != null ? safeAttr(mbs, cfgOn, "MaximumPoolSize", -1L) : -1L)));
        p.put("minIdle",     toLong(safeAttr(mbs, statsOn, "MinimumIdle",
                             cfgOn != null ? safeAttr(mbs, cfgOn, "MinimumIdle", -1L) : -1L)));
        return p;
    }

    private List<Map<String, Object>> queryLocalPools(MBeanServer mbs, String patternStr, String poolType) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Set<ObjectName> names = mbs.queryNames(new ObjectName(patternStr), null);
            for (ObjectName on : names) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("poolType", poolType);
                p.put("poolName", on.getKeyProperty("pool") != null ? on.getKeyProperty("pool")
                    : on.getKeyProperty("name") != null ? on.getKeyProperty("name") : on.toString());
                p.put("totalConnections",  toLong(safeAttr(mbs, on, "TotalConnections",  safeAttr(mbs, on, "numConnections", -1L))));
                p.put("activeConnections", toLong(safeAttr(mbs, on, "ActiveConnections",  safeAttr(mbs, on, "numBusyConnections", -1L))));
                p.put("idleConnections",   toLong(safeAttr(mbs, on, "IdleConnections",    safeAttr(mbs, on, "numIdleConnections", -1L))));
                p.put("pendingThreads",    toLong(safeAttr(mbs, on, "ThreadsAwaitingConnection", safeAttr(mbs, on, "numThreadsAwaitingCheckout", -1L))));
                p.put("maxPoolSize",       toLong(safeAttr(mbs, on, "MaximumPoolSize",    safeAttr(mbs, on, "maxPoolSize", -1L))));
                p.put("minIdle",           toLong(safeAttr(mbs, on, "MinimumIdle",        safeAttr(mbs, on, "minPoolSize", -1L))));
                result.add(p);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private Object safeAttr(MBeanServer mbs, ObjectName on, String attr, Object def) {
        try { return mbs.getAttribute(on, attr); } catch (Exception e) { return def; }
    }
    private long toLong(Object v) { return v instanceof Number ? ((Number)v).longValue() : -1L; }

    private static int stateOrder(String s) {
        return switch (s) {
            case "BLOCKED"       -> 0;
            case "WAITING"       -> 1;
            case "TIMED_WAITING" -> 2;
            case "RUNNABLE"      -> 3;
            default              -> 4;
        };
    }
}
