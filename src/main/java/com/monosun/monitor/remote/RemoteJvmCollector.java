package com.monosun.monitor.remote;

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

    private static int stateOrder(String state) {
        return switch (state) {
            case "BLOCKED"       -> 0;
            case "WAITING"       -> 1;
            case "TIMED_WAITING" -> 2;
            case "RUNNABLE"      -> 3;
            default              -> 4;
        };
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
