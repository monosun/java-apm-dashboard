package com.monosun.monitor.remote;

import com.monosun.monitor.agent.AgentThreadCollector;
import com.monosun.monitor.core.RequestLogger;
import javax.management.*;
import javax.management.remote.*;
import java.io.IOException;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * JMX RMI 를 통해 원격 JVM 의 메트릭을 수집합니다.
 *
 * 대상 JVM 시작 옵션 예시:
 *   -Dcom.sun.management.jmxremote
 *   -Dcom.sun.management.jmxremote.port=9999
 *   -Dcom.sun.management.jmxremote.authenticate=false
 *   -Dcom.sun.management.jmxremote.ssl=false
 *
 * 사용법:
 *   RemoteJvmCollector rc = new RemoteJvmCollector("10.0.0.1", 9999, "", "");
 *   rc.connect();
 *   rc.startPolling(5, scheduledExecutorService);
 *   Map<String, Object> snap = rc.getSnapshot();
 */
public class RemoteJvmCollector implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RemoteJvmCollector.class.getName());
    private static final ObjectName OS_OBJECT_NAME;

    private final RequestLogger requestLogger = new RequestLogger("jmx");

    static {
        try { OS_OBJECT_NAME = new ObjectName("java.lang:type=OperatingSystem"); }
        catch (MalformedObjectNameException e) { throw new ExceptionInInitializerError(e); }
    }

    private final String host;
    private final int    port;
    private final String user;
    private final String password;

    // JMX 연결 상태
    private JMXConnector           connector;
    private MBeanServerConnection  mbsc;

    // 원격 MXBean 프록시
    private MemoryMXBean         memoryMX;
    private ThreadMXBean         threadMX;
    private RuntimeMXBean        runtimeMX;
    private ClassLoadingMXBean   classloMX;
    private List<GarbageCollectorMXBean> gcBeans;
    private List<MemoryPoolMXBean>       memPoolBeans;

    // 상태
    private volatile boolean                   connected       = false;
    private volatile long                      lastCollectedAt = 0;
    private volatile String                    lastError       = "";
    private volatile Map<String, Object>       snapshot        = new LinkedHashMap<>();
    private volatile List<Map<String, Object>> threadList      = List.of();

    public RemoteJvmCollector(String host, int port, String user, String password) {
        this.host     = host;
        this.port     = port;
        this.user     = user;
        this.password = password;
    }

    // ── 연결 ─────────────────────────────────────────────────────────────────

    public boolean connect() {
        disconnect();
        String url = "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi";
        try {
            JMXServiceURL serviceUrl = new JMXServiceURL(url);
            Map<String, Object> env = new HashMap<>();
            if (user != null && !user.isEmpty()) {
                env.put(JMXConnector.CREDENTIALS, new String[]{user, password != null ? password : ""});
            }
            connector    = JMXConnectorFactory.connect(serviceUrl, env);
            mbsc         = connector.getMBeanServerConnection();
            memoryMX     = ManagementFactory.newPlatformMXBeanProxy(mbsc, ManagementFactory.MEMORY_MXBEAN_NAME,        MemoryMXBean.class);
            threadMX     = ManagementFactory.newPlatformMXBeanProxy(mbsc, ManagementFactory.THREAD_MXBEAN_NAME,        ThreadMXBean.class);
            runtimeMX    = ManagementFactory.newPlatformMXBeanProxy(mbsc, ManagementFactory.RUNTIME_MXBEAN_NAME,       RuntimeMXBean.class);
            classloMX    = ManagementFactory.newPlatformMXBeanProxy(mbsc, ManagementFactory.CLASS_LOADING_MXBEAN_NAME, ClassLoadingMXBean.class);
            gcBeans      = ManagementFactory.getPlatformMXBeans(mbsc, GarbageCollectorMXBean.class);
            memPoolBeans = ManagementFactory.getPlatformMXBeans(mbsc, MemoryPoolMXBean.class);
            connected    = true;
            lastError    = "";
            collect();
            LOG.info("[RemoteJvmCollector] 연결 성공: " + host + ":" + port);
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            connected = false;
            LOG.warning("[RemoteJvmCollector] 연결 실패 (" + host + ":" + port + "): " + e.getMessage());
            return false;
        }
    }

    // ── 수집 ─────────────────────────────────────────────────────────────────

    public void collect() {
        if (!connected) return;
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("remote.target", host + ":" + port);

            // Heap
            MemoryUsage heap    = memoryMX.getHeapMemoryUsage();
            long        heapMax = heap.getMax();
            m.put("heap.used.mb",        heap.getUsed() / 1_048_576L);
            m.put("heap.committed.mb",   heap.getCommitted() / 1_048_576L);
            m.put("heap.max.mb",         heapMax / 1_048_576L);
            m.put("heap.usage.percent",  heapMax <= 0 ? "0.0%" :
                    String.format("%.1f%%", (double) heap.getUsed() / heapMax * 100.0));

            // Non-heap / Metaspace / OldGen / Eden
            MemoryUsage nonHeap = memoryMX.getNonHeapMemoryUsage();
            m.put("nonheap.used.mb", nonHeap.getUsed() / 1_048_576L);
            for (MemoryPoolMXBean pool : memPoolBeans) {
                String     name  = pool.getName();
                MemoryUsage usage = pool.getUsage();
                long        used  = usage.getUsed() / 1_048_576L;
                if (name.contains("Metaspace"))               m.put("metaspace.used.mb", used);
                else if (name.contains("Eden"))               m.put("eden.used.mb", used);
                else if (name.contains("Old") || name.contains("Tenured")) m.put("oldgen.used.mb", used);
                else if (name.contains("Survivor"))           m.put("survivor.used.mb", used);
                else if (name.contains("Code"))               m.put("code.cache.used.mb", used);
            }

            // GC
            long gcCount = 0, gcTime = 0;
            for (GarbageCollectorMXBean gc : gcBeans) {
                long c = gc.getCollectionCount();
                long t = gc.getCollectionTime();
                if (c >= 0) gcCount += c;
                if (t >= 0) gcTime  += t;
            }
            m.put("gc.total.count",   gcCount);
            m.put("gc.total.time.ms", gcTime);

            // Threads
            m.put("thread.count",  threadMX.getThreadCount());
            m.put("thread.daemon", threadMX.getDaemonThreadCount());
            m.put("thread.peak",   threadMX.getPeakThreadCount());
            int blocked = 0, waiting = 0;
            ThreadInfo[] infos = threadMX.getThreadInfo(threadMX.getAllThreadIds(), 0);
            for (ThreadInfo ti : infos) {
                if (ti == null) continue;
                Thread.State st = ti.getThreadState();
                if (st == Thread.State.BLOCKED)                                    blocked++;
                if (st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING) waiting++;
            }
            m.put("thread.blocked", blocked);
            m.put("thread.waiting", waiting);

            // CPU & OS (via raw MBean attributes for compatibility)
            m.put("cpu.process", String.format("%.1f%%", getOsDouble("ProcessCpuLoad", -1.0) * 100.0));
            m.put("cpu.system",  String.format("%.1f%%", getOsDouble("CpuLoad",         -1.0) * 100.0));
            m.put("os.total.memory.mb", getOsLong("TotalMemorySize",  -1L) / 1_048_576L);
            m.put("os.free.memory.mb",  getOsLong("FreeMemorySize",   -1L) / 1_048_576L);
            m.put("os.total.swap.mb",   getOsLong("TotalSwapSpaceSize", -1L) / 1_048_576L);
            m.put("os.free.swap.mb",    getOsLong("FreeSwapSpaceSize",  -1L) / 1_048_576L);

            // Runtime
            m.put("uptime.ms",      runtimeMX.getUptime());
            m.put("loaded.classes", classloMX.getLoadedClassCount());
            m.put("jvm.name",       runtimeMX.getVmName());
            m.put("jvm.version",    runtimeMX.getVmVersion());

            this.snapshot        = m;
            this.lastCollectedAt = System.currentTimeMillis();

            collectThreadDetails();
        } catch (Exception e) {
            lastError = e.getMessage();
            connected = false;
            LOG.warning("[RemoteJvmCollector] 수집 오류 — 연결 해제: " + e.getMessage());
        }
    }

    // ── 스레드 상세 수집 ──────────────────────────────────────────────────────

    private void collectThreadDetails() {
        try {
            long[] ids = threadMX.getAllThreadIds();
            if (ids.length > 200) ids = Arrays.copyOf(ids, 200); // JMX 부하 제한

            ThreadInfo[] infos = threadMX.getThreadInfo(ids, 5); // 스택 5 프레임
            boolean cpuOk = threadMX.isThreadCpuTimeSupported() && threadMX.isThreadCpuTimeEnabled();

            // TraceRegistry MBean 에서 traceId 맵 읽기 (원격 JVM에 필터가 설치된 경우)
            Map<Long, String> traceMap = readRemoteTraceRegistry();

            List<Map<String, Object>> result = new ArrayList<>();
            for (ThreadInfo ti : infos) {
                if (ti == null) continue;
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id",       ti.getThreadId());
                t.put("name",     ti.getThreadName());
                t.put("state",    ti.getThreadState().name());
                t.put("daemon",   ti.isDaemon());
                t.put("priority", ti.getPriority());

                if (ti.getLockName()      != null) t.put("lockName",  ti.getLockName());
                if (ti.getLockOwnerName() != null) t.put("lockOwner", ti.getLockOwnerName());

                if (cpuOk) {
                    long ns = threadMX.getThreadCpuTime(ti.getThreadId());
                    if (ns >= 0) t.put("cpuMs", ns / 1_000_000L);
                }

                String traceId = traceMap.get(ti.getThreadId());
                if (traceId != null) t.put("traceId", traceId);

                StackTraceElement[] stack = ti.getStackTrace();
                if (stack.length > 0) {
                    t.put("topFrame", stack[0].toString());
                    if (stack.length > 1) t.put("frame2", stack[1].toString());
                }
                result.add(t);
            }

            // 정렬: BLOCKED → WAITING → TIMED_WAITING → RUNNABLE, CPU 내림차순
            result.sort((a, b) -> {
                int oa = stateOrder(a.get("state").toString());
                int ob = stateOrder(b.get("state").toString());
                if (oa != ob) return oa - ob;
                long ca = a.containsKey("cpuMs") ? (Long) a.get("cpuMs") : 0L;
                long cb = b.containsKey("cpuMs") ? (Long) b.get("cpuMs") : 0L;
                return Long.compare(cb, ca);
            });

            this.threadList = result;
        } catch (Exception e) {
            LOG.fine("[RemoteJvmCollector] 스레드 상세 수집 오류: " + e.getMessage());
        }
    }

    /** 원격 JVM 의 TraceRegistry MBean 에서 threadId→traceId 맵 읽기 */
    private Map<Long, String> readRemoteTraceRegistry() {
        if (mbsc == null) return Collections.emptyMap();
        try {
            ObjectName on = new ObjectName("com.monosun.monitor:type=TraceRegistry");
            if (mbsc.queryNames(on, null).isEmpty()) return Collections.emptyMap();
            String json = (String) mbsc.getAttribute(on, "ActiveTraces");
            return AgentThreadCollector.parseTraceJson(json);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static int stateOrder(String state) {
        return switch (state) {
            case "BLOCKED"       -> 0;
            case "WAITING"       -> 1;
            case "TIMED_WAITING" -> 2;
            case "RUNNABLE"      -> 3;
            default              -> 4;
        };
    }

    // ── 스레드 덤프 / 상세 ────────────────────────────────────────────────────

    /** 원격 JVM 전체 스레드 덤프 (jstack 형식 text/plain) */
    public String getThreadDump() {
        if (!connected) return "원격 JVM에 연결되어 있지 않습니다.";
        try {
            ThreadInfo[] infos = threadMX.dumpAllThreads(true, true);
            long[] deadlocked  = findDeadlockedThreads();
            Set<Long> deadSet  = new java.util.HashSet<>();
            for (long id : deadlocked) deadSet.add(id);

            StringBuilder sb = new StringBuilder();
            sb.append("Full Thread Dump (Remote) — ").append(java.time.Instant.now()).append('\n');
            sb.append("target=").append(host).append(':').append(port)
              .append(" threads=").append(threadMX.getThreadCount()).append("\n\n");

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
                        sb.append(" owned by \"").append(ti.getLockOwnerName()).append('"');
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
        } catch (Exception e) {
            return "Thread dump 실패: " + e.getMessage();
        }
    }

    /** 원격 JVM 단일 스레드 상세 (전체 스택 + 잠금 정보) */
    public Map<String, Object> getThreadDetail(long id) {
        if (!connected) return null;
        try {
            ThreadInfo[] infos = threadMX.getThreadInfo(new long[]{id}, Integer.MAX_VALUE);
            if (infos.length == 0 || infos[0] == null) return null;
            ThreadInfo ti = infos[0];
            boolean cpuOk = threadMX.isThreadCpuTimeSupported() && threadMX.isThreadCpuTimeEnabled();

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id",        ti.getThreadId());
            t.put("name",      ti.getThreadName());
            t.put("state",     ti.getThreadState().name());
            t.put("daemon",    ti.isDaemon());
            t.put("priority",  ti.getPriority());
            t.put("inNative",  ti.isInNative());
            t.put("suspended", ti.isSuspended());
            if (cpuOk) {
                long cpuNs = threadMX.getThreadCpuTime(id);
                if (cpuNs >= 0) t.put("cpuMs", cpuNs / 1_000_000L);
            }
            if (ti.getLockName()      != null) t.put("lockName",    ti.getLockName());
            if (ti.getLockOwnerName() != null) t.put("lockOwner",   ti.getLockOwnerName());
            if (ti.getLockOwnerId()   >= 0)    t.put("lockOwnerId", ti.getLockOwnerId());

            StackTraceElement[] stack = ti.getStackTrace();
            List<String> frames = new ArrayList<>();
            for (StackTraceElement e : stack) frames.add(e.toString());
            t.put("stackTrace", frames);
            t.put("stackDepth", frames.size());
            return t;
        } catch (Exception e) {
            LOG.fine("[RemoteJvmCollector] 스레드 상세 조회 오류: " + e.getMessage());
            return null;
        }
    }

    // ── HTTP 요청 처리기 (Tomcat / Spring Boot) ──────────────────────────────

    /**
     * Tomcat RequestProcessor MBean에서 활성 HTTP 요청 정보를 수집합니다.
     * 대상 JVM이 Tomcat 기반(Spring Boot 내장 포함)일 때만 데이터가 반환됩니다.
     * ObjectName 패턴: Catalina:type=RequestProcessor,*
     */
    public List<Map<String, Object>> getRequestProcessors() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!connected) return result;
        try {
            ObjectName pattern = new ObjectName("Catalina:type=RequestProcessor,*");
            Set<ObjectName> names = mbsc.queryNames(pattern, null);
            for (ObjectName on : names) {
                try {
                    Map<String, Object> r = new LinkedHashMap<>();

                    // currentThreadName이 빈 문자열이면 ObjectName의 name 키로 폴백
                    String threadName = String.valueOf(safeAttr(on, "currentThreadName", ""));
                    if (threadName.isEmpty()) {
                        String nameKey = on.getKeyProperty("name");
                        if (nameKey != null) threadName = nameKey;
                    }
                    r.put("worker", threadName);

                    r.put("stage",        safeAttr(on, "stage", -1));
                    r.put("currentUri",   safeAttr(on, "currentUri", ""));
                    r.put("method",       safeAttr(on, "method", ""));
                    r.put("queryString",  safeAttr(on, "currentQueryString", ""));
                    r.put("contentType",  safeAttr(on, "contentType", ""));
                    r.put("virtualHost",  safeAttr(on, "virtualHost", ""));

                    // IPv6 루프백 정규화
                    String remoteAddr = String.valueOf(safeAttr(on, "remoteAddr", ""));
                    if ("0:0:0:0:0:0:0:1".equals(remoteAddr) || "::1".equals(remoteAddr))
                        remoteAddr = "127.0.0.1";
                    r.put("remoteAddr", remoteAddr);

                    Object pt = safeAttr(on, "processingTime", 0L);
                    r.put("processingTimeMs", pt instanceof Number ? ((Number)pt).longValue() : 0L);
                    r.put("requestCount",  safeAttr(on, "requestCount", 0L));
                    r.put("errorCount",    safeAttr(on, "errorCount", 0L));
                    r.put("bytesSent",     safeAttr(on, "bytesSent", 0L));
                    r.put("bytesReceived", safeAttr(on, "bytesReceived", 0L));
                    result.add(r);
                } catch (Exception ignored) {}
            }
            result.sort((a, b) -> {
                long ta = toLong(a.get("processingTimeMs"));
                long tb = toLong(b.get("processingTimeMs"));
                return Long.compare(tb, ta);
            });

            // 평균 처리시간 계산 (활성 요청만 포함)
            long activeCount = result.stream()
                .filter(r -> !String.valueOf(r.getOrDefault("currentUri", "")).isEmpty())
                .count();
            if (activeCount > 0) {
                long sumMs = result.stream()
                    .filter(r -> !String.valueOf(r.getOrDefault("currentUri", "")).isEmpty())
                    .mapToLong(r -> toLong(r.get("processingTimeMs")))
                    .filter(v -> v >= 0)
                    .sum();
                result.forEach(r -> r.put("avgProcessingTimeMs", sumMs / activeCount));
            }

            requestLogger.logIfNew(result);
        } catch (Exception e) {
            LOG.fine("[RemoteJvmCollector] RequestProcessor 수집 오류: " + e.getMessage());
        }
        return result;
    }

    // ── DB 커넥션 풀 (HikariCP / Tomcat JDBC / DBCP2) ─────────────────────────

    /**
     * 여러 커넥션 풀 MBean 패턴을 순서대로 시도해 활성 풀 정보를 수집합니다.
     * HikariCP, Tomcat JDBC Pool, Commons DBCP2를 자동 감지합니다.
     */
    public List<Map<String, Object>> getConnectionPools() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!connected) return result;
        // HikariCP
        result.addAll(queryPools("com.zaxxer.hikari:type=PoolStats,*", "hikari"));
        // Tomcat JDBC Pool
        result.addAll(queryPools("Catalina:type=DataSource,*", "tomcat-jdbc"));
        // Apache Commons DBCP2
        result.addAll(queryPools("org.apache.commons.pool2:type=GenericObjectPool,*", "dbcp2"));
        return result;
    }

    private List<Map<String, Object>> queryPools(String patternStr, String poolType) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            ObjectName pattern = new ObjectName(patternStr);
            Set<ObjectName> names = mbsc.queryNames(pattern, null);
            for (ObjectName on : names) {
                try {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("poolType", poolType);
                    p.put("poolName", on.getKeyProperty("pool") != null
                        ? on.getKeyProperty("pool") : on.getKeyProperty("name") != null
                        ? on.getKeyProperty("name") : on.toString());
                    // HikariCP attributes
                    p.put("totalConnections",  toLong(safeAttr(on, "TotalConnections",  safeAttr(on, "numConnections", -1L))));
                    p.put("activeConnections", toLong(safeAttr(on, "ActiveConnections",  safeAttr(on, "numBusyConnections", -1L))));
                    p.put("idleConnections",   toLong(safeAttr(on, "IdleConnections",    safeAttr(on, "numIdleConnections", -1L))));
                    p.put("pendingThreads",    toLong(safeAttr(on, "ThreadsAwaitingConnection", safeAttr(on, "numThreadsAwaitingCheckout", -1L))));
                    p.put("maxPoolSize",       toLong(safeAttr(on, "MaximumPoolSize",    safeAttr(on, "maxPoolSize", -1L))));
                    p.put("minIdle",           toLong(safeAttr(on, "MinimumIdle",        safeAttr(on, "minPoolSize", -1L))));
                    result.add(p);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOG.fine("[RemoteJvmCollector] DB Pool 수집 오류 (" + poolType + "): " + e.getMessage());
        }
        return result;
    }

    private Object safeAttr(ObjectName on, String attr, Object def) {
        try { return mbsc.getAttribute(on, attr); } catch (Exception e) { return def; }
    }
    private long toLong(Object v) { return v instanceof Number ? ((Number)v).longValue() : -1L; }

    /** 원격 JVM 데드락 탐지 */
    public long[] findDeadlockedThreads() {
        if (!connected) return new long[0];
        try {
            long[] d = threadMX.findDeadlockedThreads();
            return d != null ? d : new long[0];
        } catch (Exception e) { return new long[0]; }
    }

    // ── 폴링 스케줄러 ────────────────────────────────────────────────────────

    public void startPolling(int pollSec, int reconnectSec, ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(() -> {
            if (connected) {
                collect();
                // 임계치 초과 체크
            } else {
                LOG.info("[RemoteJvmCollector] 재연결 시도: " + host + ":" + port);
                connect();
            }
        }, pollSec, pollSec, TimeUnit.SECONDS);
    }

    // ── 상태 JSON ────────────────────────────────────────────────────────────

    public String statusJson() {
        long age = lastCollectedAt > 0 ? System.currentTimeMillis() - lastCollectedAt : -1;
        return String.format(
            "{\"connected\":%b,\"target\":\"%s:%d\",\"lastCollectedMs\":%d,\"error\":\"%s\"}",
            connected, host, port, age,
            lastError.replace("\"", "'"));
    }

    // ── 공개 접근자 ──────────────────────────────────────────────────────────

    public Map<String, Object>       getSnapshot()        { return snapshot; }
    public List<Map<String, Object>> getThreadList()      { return threadList; }
    public boolean                   isConnected()        { return connected; }
    public String                    getTarget()          { return host + ":" + port; }
    public long                      getLastCollectedAt() { return lastCollectedAt; }

    // ── 연결 해제 ────────────────────────────────────────────────────────────

    public void disconnect() {
        connected = false;
        if (connector != null) {
            try { connector.close(); } catch (IOException ignored) {}
            connector = null;
        }
    }

    @Override
    public void close() { disconnect(); }

    // ── MBean 원시 속성 접근 (OS 호환성 위해 프록시 대신 사용) ──────────────

    private double getOsDouble(String attr, double def) {
        try {
            Object v = mbsc.getAttribute(OS_OBJECT_NAME, attr);
            return v instanceof Number ? ((Number) v).doubleValue() : def;
        } catch (Exception e) { return def; }
    }

    private long getOsLong(String attr, long def) {
        try {
            Object v = mbsc.getAttribute(OS_OBJECT_NAME, attr);
            return v instanceof Number ? ((Number) v).longValue() : def;
        } catch (Exception e) { return def; }
    }
}
